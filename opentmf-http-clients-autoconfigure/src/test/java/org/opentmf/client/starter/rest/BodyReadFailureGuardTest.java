package org.opentmf.client.starter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

/**
 * A request log that reads the whole body and drops its read failure, as Logbook's interceptor
 * does, cannot hand the caller a body cut short as an end of stream.
 */
class BodyReadFailureGuardTest {

  /** Reads 3 bytes, then fails the way Apache's stream does after a read timeout. */
  private static final class CutAfterThreeBytes extends InputStream {

    private int sent;
    private boolean failed;

    @Override
    public int read() throws IOException {
      if (failed) {
        return -1;
      }
      if (sent < 3) {
        return 'a' + sent++;
      }
      failed = true;
      throw new SocketTimeoutException("Read timed out");
    }

    /** As a socket stream reads: what has arrived, then the failure on the next read. */
    @Override
    public int read(byte[] bytes, int off, int len) throws IOException {
      if (len == 0) {
        return 0;
      }
      if (sent < 3) {
        int n = Math.min(len, 3 - sent);
        for (int i = 0; i < n; i++) {
          bytes[off + i] = (byte) read();
        }
        return n;
      }
      int b = read();
      return b == -1 ? -1 : 1;
    }
  }

  /** Logbook's way: buffer the whole body, drop a failure, hand the response on. */
  private static final ClientHttpRequestInterceptor DROPS_ITS_READ_FAILURE =
      (request, body, execution) -> {
        var response = execution.execute(request, body);
        try {
          response.getBody().readAllBytes();
        } catch (IOException dropped) {
          // "Unable to log response"
        }
        return response;
      };

  private static ClientHttpResponse through(ClientHttpRequestInterceptor log, InputStream body)
      throws IOException {
    var guard = new BodyReadFailureGuard(log);
    return guard.intercept(new MockClientHttpRequest(HttpMethod.GET, URI.create("http://peer/x")),
        new byte[0], (request, sent) -> {
          var response = new MockClientHttpResponse(body, HttpStatus.OK);
          response.getHeaders().add("X-Peer", "kept");
          return response;
        });
  }

  @Test
  void aBodyTheLogFailedToRead_raisesThatFailure_onEveryLaterRead() throws IOException {
    var response = through(DROPS_ITS_READ_FAILURE, new CutAfterThreeBytes());

    var first = catchThrowable(() -> response.getBody().read());
    var again = catchThrowable(() -> response.getBody().read(new byte[8], 0, 8));
    var skipped = catchThrowable(() -> response.getBody().skip(1));

    assertThat(first).isInstanceOf(SocketTimeoutException.class).hasMessage("Read timed out");
    assertThat(again).isSameAs(first);
    assertThat(skipped).isSameAs(first);
  }

  @Test
  void withoutTheGuard_theSameLog_handsOnAnEndOfStream() throws IOException {
    var unguarded = new AtomicReference<ClientHttpResponse>();
    DROPS_ITS_READ_FAILURE.intercept(
        new MockClientHttpRequest(HttpMethod.GET, URI.create("http://peer/x")), new byte[0],
        (request, sent) -> {
          var response = new MockClientHttpResponse(new CutAfterThreeBytes(), HttpStatus.OK);
          unguarded.set(response);
          return response;
        });

    assertThat(unguarded.get().getBody().read()).isEqualTo(-1);
  }

  @Test
  void aBodyReadWithoutAFailure_passesUnchanged_withStatusAndHeaders() throws IOException {
    ClientHttpRequestInterceptor passing = (request, body, execution) ->
        execution.execute(request, body);
    var bytes = "abcdefgh".getBytes(StandardCharsets.US_ASCII);

    try (var response = through(passing, new ByteArrayInputStream(bytes))) {
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getStatusText()).isEqualTo("OK");
      assertThat(response.getHeaders().getFirst("X-Peer")).isEqualTo("kept");
      assertThat(response.getBody()).isSameAs(response.getBody());
      assertThat(response.getBody().skip(1)).isEqualTo(1);
      assertThat(response.getBody().read()).isEqualTo('b');
      assertThat(response.getBody().readAllBytes())
          .isEqualTo("cdefgh".getBytes(StandardCharsets.US_ASCII));
    }
  }
}
