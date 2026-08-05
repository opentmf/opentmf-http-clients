package org.opentmf.client.common.exception;

import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import lombok.AccessLevel;
import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

@Getter
public class OpenTmfClientResponseException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 5L;

  private final HttpStatusCode statusCode;
  private final String responseBody;

  /**
   * Response details captured on the error path, set once after construction.
   *
   * <p>An {@link AtomicReference} rather than two plain fields for two reasons: it keeps the
   * field {@code final} despite the late assignment, and — the reason that matters — it publishes
   * the details safely. The exception is created on the thread that handled the response (a Netty
   * event loop, on the reactive path) and read on whichever thread runs the retry logic; plain
   * non-final fields would carry no happens-before guarantee across that hand-off.</p>
   */
  @Getter(AccessLevel.NONE)
  private final AtomicReference<ResponseDetails> responseDetails = new AtomicReference<>();

  /**
   * Headers and parsed {@code Retry-After} as captured at the moment of the error — the only
   * point at which they are still reachable, since the response is closed before callers see it.
   */
  private record ResponseDetails(@Nullable HttpHeaders headers, @Nullable Duration retryAfter)
      implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
  }

  public OpenTmfClientResponseException(HttpStatusCode statusCode) {
    this.statusCode = statusCode;
    this.responseBody = null;
  }

  public OpenTmfClientResponseException(HttpStatusCode statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
    this.responseBody = null;
  }

  public OpenTmfClientResponseException(HttpStatusCode statusCode, String message,
      String responseBody) {
    super(message);
    this.statusCode = statusCode;
    this.responseBody = responseBody;
  }

  public OpenTmfClientResponseException(HttpStatusCode statusCode, String message,
      Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
    this.responseBody = null;
  }

  public final int getRawStatusCode() {
    return statusCode.value();
  }

  /**
   * Response headers, captured on the error path only. {@code null} unless the exception came
   * from one of the library's error handlers.
   */
  public final @Nullable HttpHeaders getHeaders() {
    var details = responseDetails.get();
    return details == null ? null : details.headers();
  }

  /**
   * The server's parsed {@code Retry-After}, or {@code null} when the header was absent,
   * unparseable, or arrived on a status this library does not consider retryable. Un-capped —
   * the caller's {@code max-retry-after} decides whether it is honoured or refused.
   */
  public final @Nullable Duration getRetryAfter() {
    var details = responseDetails.get();
    return details == null ? null : details.retryAfter();
  }

  /**
   * Attaches the captured response details. Deliberately a post-construction setter rather than
   * a constructor parameter: {@link org.opentmf.client.common.util.HttpClientUtil} resolves this
   * hierarchy's constructors reflectively by exact signature, so the existing four must stay as
   * they are, and consumer subclasses cannot be expected to declare a wider one.
   *
   * @param headers    response headers; stored as a read-only copy
   * @param retryAfter parsed {@code Retry-After}, or {@code null}
   */
  public final void setResponseDetails(@Nullable HttpHeaders headers,
      @Nullable Duration retryAfter) {
    // copyOf first: readOnlyHttpHeaders alone is a read-only *view*, so later mutation (or
    // recycling) of the live response's headers would show through this exception.
    var copied = headers == null
        ? null
        : HttpHeaders.readOnlyHttpHeaders(HttpHeaders.copyOf(headers));
    responseDetails.set(new ResponseDetails(copied, retryAfter));
  }
}
