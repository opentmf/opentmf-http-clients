package org.opentmf.client.starter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.concurrent.Cancellable;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.starter.rest.ExchangeTimeoutRequestFactory.BoundedStream;
import org.opentmf.client.starter.rest.ExchangeTimeoutRequestFactory.Deadline;
import org.springframework.http.HttpMethod;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * The parts of the Apache whole-exchange bound that the timing cells of
 * {@code ExchangeTimeoutIT} do not reach one by one.
 */
class ExchangeTimeoutRequestFactoryTest {

  private static final Duration LONG = Duration.ofMinutes(5);

  /** A request that records its cancellation. */
  private static final class Cancelled implements Cancellable {

    private final CountDownLatch latch = new CountDownLatch(1);

    @Override
    public boolean cancel() {
      latch.countDown();
      return true;
    }

    boolean await() throws InterruptedException {
      return latch.await(10, TimeUnit.SECONDS);
    }

    boolean happened() {
      return latch.getCount() == 0;
    }
  }

  /** A deadline that has passed, its request cancelled. */
  private static Deadline passed() throws InterruptedException {
    var request = new Cancelled();
    var deadline = new Deadline(request, Duration.ofMillis(1));
    assertThat(request.await()).isTrue();
    return deadline;
  }

  private static InputStream bytes(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII));
  }

  /** A body that has failed on the connection. */
  private static InputStream failing(IOException failure) {
    return new InputStream() {
      @Override
      public int read() throws IOException {
        throw failure;
      }

      @Override
      public long skip(long n) throws IOException {
        throw failure;
      }
    };
  }

  @Test
  void aBodyReadInsideTheBound_passesUnchanged_andItsEndDisarmsTheBound() throws Exception {
    var request = new Cancelled();
    var deadline = new Deadline(request, LONG);

    try (var body = new BoundedStream(bytes("abcdefgh"), deadline)) {
      assertThat(body.read()).isEqualTo('a');
      assertThat(body.skip(1)).isEqualTo(1);
      assertThat(body.readAllBytes()).isEqualTo("cdefgh".getBytes(StandardCharsets.US_ASCII));
      assertThat(body.read()).isEqualTo(-1);
    }
    assertThat(deadline.passed()).isFalse();
    assertThat(request.happened()).isFalse();
  }

  @Test
  void onceTheBoundHasPassed_everyReadRaisesIt() throws Exception {
    var body = new BoundedStream(bytes("abcdefgh"), passed());

    assertThat(catchThrowable(body::read))
        .isExactlyInstanceOf(SocketTimeoutException.class)
        .hasMessage("exchange-timeout of 1 ms passed before the response was fully received")
        .hasNoCause();
    assertThat(catchThrowable(() -> body.read(new byte[4], 0, 4)))
        .isExactlyInstanceOf(SocketTimeoutException.class);
    assertThat(catchThrowable(() -> body.skip(1)))
        .isExactlyInstanceOf(SocketTimeoutException.class);
  }

  @Test
  void theReadTheCutEnded_raisesTheBound_causedByWhatTheCutDid() {
    var request = new Cancelled();
    var deadline = new Deadline(request, Duration.ofMillis(50));
    var closed = new IOException("Socket closed");
    // a read already blocked when the cut comes, ended by it
    var blocked = new InputStream() {
      @Override
      public int read() throws IOException {
        try {
          if (!request.await()) {
            throw new IOException("the cut never came");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        throw closed;
      }
    };
    var body = new BoundedStream(blocked, deadline);

    assertThat(catchThrowable(body::read))
        .isExactlyInstanceOf(SocketTimeoutException.class)
        .hasCause(closed);
  }

  @Test
  void aBodyThatEndsAfterTheBound_wasCutShort_andRaisesIt() throws Exception {
    var deadline = passed();
    var endedEarly = new InputStream() {
      @Override
      public int read() {
        return -1;
      }
    };
    var body = new BoundedStream(endedEarly, deadline);

    assertThat(catchThrowable(body::read))
        .isExactlyInstanceOf(SocketTimeoutException.class);
  }

  @Test
  void aConnectionFailureInsideTheBound_isRaisedAsItIs() {
    var refused = new ConnectException("Connection refused");
    var body = new BoundedStream(failing(refused), new Deadline(new Cancelled(), LONG));

    assertThat(catchThrowable(body::read)).isSameAs(refused);
    assertThat(catchThrowable(() -> body.read(new byte[4], 0, 4))).isSameAs(refused);
    assertThat(catchThrowable(() -> body.skip(1))).isSameAs(refused);
  }

  @Test
  void theRequest_delegatesEverythingButTheSend() throws Exception {
    var factory = new ExchangeTimeoutRequestFactory(HttpClients.createDefault(), LONG);
    var uri = URI.create("http://localhost:1/x");

    var request = factory.createRequest(uri, HttpMethod.POST);
    request.getHeaders().add("X-Kept", "yes");
    request.getAttributes().put("kept", true);
    ((StreamingHttpOutputMessage) request).setBody(out -> out.write('x'));

    assertThat(request.getMethod()).isEqualTo(HttpMethod.POST);
    assertThat(request.getURI()).isEqualTo(uri);
    assertThat(request.getHeaders().getFirst("X-Kept")).isEqualTo("yes");
    assertThat(request.getAttributes()).containsEntry("kept", true);
    assertThat(factory.exchangeTimeout()).isEqualTo(LONG);
  }

  @Test
  void aSendThatFailsInsideTheBound_failsAsItIs() throws Exception {
    int closedPort;
    try (var socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    var factory = new ExchangeTimeoutRequestFactory(HttpClients.createDefault(), LONG);
    var request = factory.createRequest(URI.create("http://localhost:" + closedPort + "/x"),
        HttpMethod.GET);

    assertThat(catchThrowable(request::execute)).isInstanceOf(IOException.class)
        .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("exchange-timeout"));
  }

  @Test
  void apache_withoutExchangeTimeout_springsOwnFactory_withIt_theBoundedOne() {
    var properties = new ClientProperties();

    assertThat(ApacheRestTemplateFactory.requestFactory(HttpClients.createDefault(), properties))
        .isExactlyInstanceOf(HttpComponentsClientHttpRequestFactory.class);
    properties.setExchangeTimeout(Duration.ofSeconds(3));
    assertThat(ApacheRestTemplateFactory.requestFactory(HttpClients.createDefault(), properties))
        .isInstanceOfSatisfying(ExchangeTimeoutRequestFactory.class,
            factory -> assertThat(factory.exchangeTimeout()).isEqualTo(Duration.ofSeconds(3)));
  }

  @Test
  void jdk_theReadTimeout_isTheSmallerOfTheTwo() {
    var properties = new ClientProperties();
    properties.setResponseTimeout(Duration.ofSeconds(45));

    assertThat(JdkRestTemplateFactory.readTimeout(properties)).isEqualTo(Duration.ofSeconds(45));
    properties.setExchangeTimeout(Duration.ofSeconds(10));
    assertThat(JdkRestTemplateFactory.readTimeout(properties)).isEqualTo(Duration.ofSeconds(10));
    properties.setExchangeTimeout(Duration.ofMinutes(5));
    assertThat(JdkRestTemplateFactory.readTimeout(properties)).isEqualTo(Duration.ofSeconds(45));
  }

  @Test
  void theTokenCalls_runUnderTheClientsExchangeTimeout() {
    var client = new ClientProperties();
    client.setExchangeTimeout(Duration.ofSeconds(3));
    var token = new ClientProperties();
    token.setExchangeTimeout(Duration.ofSeconds(3));
    token.setResponseTimeout(Duration.ofSeconds(5));

    var apache = TokenRequestFactories.forTokenCalls(
        new ExchangeTimeoutRequestFactory(HttpClients.createDefault(), Duration.ofSeconds(3)),
        client, token);
    var jdk = TokenRequestFactories.forTokenCalls(
        new CloseableJdkClientHttpRequestFactory(HttpClient.newHttpClient()), client, token);

    assertThat(apache).isInstanceOfSatisfying(ExchangeTimeoutRequestFactory.class,
        factory -> assertThat(factory.exchangeTimeout()).isEqualTo(Duration.ofSeconds(3)));
    assertThat(jdk).isInstanceOf(JdkClientHttpRequestFactory.class);
  }
}
