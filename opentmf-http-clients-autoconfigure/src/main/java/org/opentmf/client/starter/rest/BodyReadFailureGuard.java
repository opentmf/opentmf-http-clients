package org.opentmf.client.starter.rest;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Wraps the request log's interceptor (Logbook's) so that a response body the log failed to read
 * never reaches the caller as an end of stream.
 *
 * <p>Logbook's interceptor reads the whole response body inside the exchange to log it. When
 * that read fails (a read timeout, a reset connection) it logs "Unable to log response", drops
 * the failure and hands the response on; on Apache the caller's next read then finds the end of
 * the stream, so a response never fully received reached the caller as a {@code 200} without a
 * body. Behind this guard every read of the body after a failed one raises that same failure, so
 * the caller sees what it sees without the request log. A body that is read without a failure
 * passes unchanged.</p>
 */
final class BodyReadFailureGuard implements ClientHttpRequestInterceptor {

  private final ClientHttpRequestInterceptor delegate;

  BodyReadFailureGuard(ClientHttpRequestInterceptor delegate) {
    this.delegate = delegate;
  }

  @Override
  public ClientHttpResponse intercept(HttpRequest request, byte[] body,
      ClientHttpRequestExecution execution) throws IOException {
    return delegate.intercept(request, body,
        (sent, sentBody) -> new GuardedResponse(execution.execute(sent, sentBody)));
  }

  /** The response with a body that remembers its first read failure. */
  private static final class GuardedResponse implements ClientHttpResponse {

    private final ClientHttpResponse delegate;
    private @Nullable InputStream body;

    private GuardedResponse(ClientHttpResponse delegate) {
      this.delegate = delegate;
    }

    @Override
    public HttpStatusCode getStatusCode() throws IOException {
      return delegate.getStatusCode();
    }

    @Override
    public String getStatusText() throws IOException {
      return delegate.getStatusText();
    }

    @Override
    public HttpHeaders getHeaders() {
      return delegate.getHeaders();
    }

    @Override
    public InputStream getBody() throws IOException {
      if (body == null) {
        body = new FailureKeepingStream(delegate.getBody());
      }
      return body;
    }

    @Override
    public void close() {
      delegate.close();
    }
  }

  /** A stream that, once a read has failed, raises that failure on every later read. */
  static final class FailureKeepingStream extends FilterInputStream {

    private @Nullable IOException failure;

    FailureKeepingStream(InputStream in) {
      super(in);
    }

    @Override
    public int read() throws IOException {
      failIfFailed();
      try {
        return super.read();
      } catch (IOException e) {
        throw remembered(e);
      }
    }

    @Override
    public int read(byte[] bytes, int off, int len) throws IOException {
      failIfFailed();
      try {
        return super.read(bytes, off, len);
      } catch (IOException e) {
        throw remembered(e);
      }
    }

    @Override
    public long skip(long n) throws IOException {
      failIfFailed();
      try {
        return super.skip(n);
      } catch (IOException e) {
        throw remembered(e);
      }
    }

    private void failIfFailed() throws IOException {
      if (failure != null) {
        throw failure;
      }
    }

    private IOException remembered(IOException e) {
      failure = e;
      return e;
    }
  }
}
