package org.opentmf.client.starter.rest;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.core5.concurrent.Cancellable;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.jspecify.annotations.Nullable;
import org.opentmf.client.common.exception.OpenTmfClientExchangeTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * An Apache request factory that bounds the whole exchange, from the send to the last byte of
 * the body, by {@code exchange-timeout}. Apache's own response timeout is silence per read, so
 * a body that keeps arriving is never cut by it.
 *
 * <p>When the bound passes, the request is cancelled: Apache closes its connection, which ends
 * a read blocked on the headers or on the body. Every read after that, and the read the cut
 * ended, raises {@link OpenTmfClientExchangeTimeoutException}; a body cut short never ends as a
 * clean end of stream. A response read to its end, or closed, before the bound disarms it.</p>
 */
final class ExchangeTimeoutRequestFactory extends HttpComponentsClientHttpRequestFactory {

  /** The request {@link #createHttpUriRequest} made on this thread, for {@link #createRequest}. */
  private static final ThreadLocal<Cancellable> CREATED = new ThreadLocal<>();

  private final Duration exchangeTimeout;

  ExchangeTimeoutRequestFactory(HttpClient httpClient, Duration exchangeTimeout) {
    super(httpClient);
    this.exchangeTimeout = exchangeTimeout;
  }

  Duration exchangeTimeout() {
    return exchangeTimeout;
  }

  @Override
  protected ClassicHttpRequest createHttpUriRequest(HttpMethod httpMethod, URI uri) {
    var request = super.createHttpUriRequest(httpMethod, uri);
    // every request the base class makes (HttpGet, HttpPost, ...) can be cancelled
    CREATED.set((Cancellable) request);
    return request;
  }

  @Override
  public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
    try {
      var request = super.createRequest(uri, httpMethod);
      return new BoundedRequest(request, CREATED.get(), exchangeTimeout);
    } finally {
      CREATED.remove();
    }
  }

  /** The one timer thread of every bounded exchange: it only cancels requests. */
  private static final class Timer {

    private static final ScheduledThreadPoolExecutor SCHEDULER = scheduler();

    private Timer() {
    }

    private static ScheduledThreadPoolExecutor scheduler() {
      var scheduler = new ScheduledThreadPoolExecutor(1, task -> {
        var thread = new Thread(task, "opentmf-exchange-timeout");
        thread.setDaemon(true);
        return thread;
      });
      scheduler.setRemoveOnCancelPolicy(true);
      return scheduler;
    }
  }

  /** One exchange's bound: armed at the send, cancels the request when it passes. */
  static final class Deadline {

    private final Duration exchangeTimeout;
    private final ScheduledFuture<?> timer;
    private volatile boolean passed;

    Deadline(Cancellable request, Duration exchangeTimeout) {
      this.exchangeTimeout = exchangeTimeout;
      this.timer = Timer.SCHEDULER.schedule(() -> {
        passed = true;
        request.cancel();
      }, exchangeTimeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    boolean passed() {
      return passed;
    }

    void disarm() {
      timer.cancel(false);
    }

    IOException exceeded(@Nullable Throwable cause) {
      return new OpenTmfClientExchangeTimeoutException(exchangeTimeout, cause);
    }
  }

  /** The request, with its bound armed when it is sent. */
  private static final class BoundedRequest implements ClientHttpRequest,
      StreamingHttpOutputMessage {

    private final ClientHttpRequest delegate;
    private final Cancellable cancellable;
    private final Duration exchangeTimeout;

    private BoundedRequest(ClientHttpRequest delegate, Cancellable cancellable,
        Duration exchangeTimeout) {
      this.delegate = delegate;
      this.cancellable = cancellable;
      this.exchangeTimeout = exchangeTimeout;
    }

    @Override
    public ClientHttpResponse execute() throws IOException {
      var deadline = new Deadline(cancellable, exchangeTimeout);
      ClientHttpResponse response = null;
      try {
        response = new BoundedResponse(delegate.execute(), deadline);
        return response;
      } catch (IOException e) {
        throw deadline.passed() ? deadline.exceeded(e) : e;
      } finally {
        if (response == null) {
          deadline.disarm();
        }
      }
    }

    @Override
    public OutputStream getBody() throws IOException {
      return delegate.getBody();
    }

    @Override
    public void setBody(Body body) {
      ((StreamingHttpOutputMessage) delegate).setBody(body);
    }

    @Override
    public HttpMethod getMethod() {
      return delegate.getMethod();
    }

    @Override
    public URI getURI() {
      return delegate.getURI();
    }

    @Override
    public Map<String, Object> getAttributes() {
      return delegate.getAttributes();
    }

    @Override
    public HttpHeaders getHeaders() {
      return delegate.getHeaders();
    }
  }

  /** The response, its body read under the bound. */
  private static final class BoundedResponse implements ClientHttpResponse {

    private final ClientHttpResponse delegate;
    private final Deadline deadline;
    private @Nullable InputStream body;

    private BoundedResponse(ClientHttpResponse delegate, Deadline deadline) {
      this.delegate = delegate;
      this.deadline = deadline;
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
        body = new BoundedStream(delegate.getBody(), deadline);
      }
      return body;
    }

    /** Closing reads what is left of the body (Spring's own close), still under the bound. */
    @Override
    public void close() {
      try {
        delegate.close();
      } finally {
        deadline.disarm();
      }
    }
  }

  /** A body that raises the bound's failure once it has passed, and never ends cut short. */
  static final class BoundedStream extends FilterInputStream {

    private final Deadline deadline;

    BoundedStream(InputStream in, Deadline deadline) {
      super(in);
      this.deadline = deadline;
    }

    @Override
    public int read() throws IOException {
      failIfPassed();
      return ended(readOrFail(() -> super.read()));
    }

    @Override
    public int read(byte[] bytes, int off, int len) throws IOException {
      failIfPassed();
      return ended(readOrFail(() -> super.read(bytes, off, len)));
    }

    @Override
    public long skip(long n) throws IOException {
      failIfPassed();
      try {
        return super.skip(n);
      } catch (IOException e) {
        throw deadline.passed() ? deadline.exceeded(e) : e;
      }
    }

    private void failIfPassed() throws IOException {
      if (deadline.passed()) {
        throw deadline.exceeded(null);
      }
    }

    private int readOrFail(Read read) throws IOException {
      try {
        return read.read();
      } catch (IOException e) {
        throw deadline.passed() ? deadline.exceeded(e) : e;
      }
    }

    /** The end of the body: before the bound it disarms it; after, the body was cut short. */
    private int ended(int read) throws IOException {
      if (read == -1) {
        failIfPassed();
        deadline.disarm();
      }
      return read;
    }

    @FunctionalInterface
    private interface Read {
      int read() throws IOException;
    }
  }
}
