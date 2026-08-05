package org.opentmf.client.reactive.util;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Set;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.util.HttpClientUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

@Slf4j
public final class WebClientUtil {

  private static final double DEFAULT_JITTER_FACTOR = 0.0d;

  /**
   * Default bound on a server-requested {@code Retry-After}, matching
   * {@code ClientProperties.maxRetryAfter}. Used by the overloads that do not take one, so a
   * caller who never configures it is still protected from an unbounded wait.
   */
  public static final Duration DEFAULT_MAX_RETRY_AFTER = Duration.ofSeconds(30);

  private WebClientUtil() {
  }

  public static Mono<Throwable> handleError(ClientResponse clientResponse,
      Class<? extends OpenTmfClientResponseException> exceptionClass) {
    var request = clientResponse.request();
    var httpStatus = clientResponse.statusCode();
    log.debug("Handling {} for {} {}", httpStatus, request.getMethod(), request.getURI());
    return clientResponse
        .bodyToMono(String.class)
        .switchIfEmpty(Mono.defer(
            () -> Mono.error(HttpClientUtil.createException(httpStatus, exceptionClass))))
        .map(message -> HttpClientUtil.createException(httpStatus, message, exceptionClass));
  }

  public static boolean shouldRetryOn(@NonNull Throwable throwable) {
    HttpStatusCode status = null;
    if (throwable instanceof OpenTmfClientResponseException e) {
      status = e.getStatusCode();
    } else if (throwable instanceof WebClientResponseException e) {
      status = e.getStatusCode();
    }
    return HttpClientUtil.isRetryableStatus(status);
  }

  public static RetryBackoffSpec retry(long maxAttempts, Duration duration, double jitterFactor) {
    return retry(maxAttempts, duration, jitterFactor, DEFAULT_MAX_RETRY_AFTER);
  }

  /**
   * Exponential-backoff retry that additionally honours a server's {@code Retry-After} within the
   * given bound.
   *
   * <p>The server controls <em>when</em>; the caller controls <em>how many</em> and <em>at most
   * how long</em>. {@code Retry-After} never changes {@code maxAttempts}, and a request to wait
   * longer than {@code maxRetryAfter} is refused outright rather than clamped.</p>
   *
   * <p>The requested delay is applied <em>in addition to</em> the spec's own backoff rather than
   * as {@code max(backoff, retryAfter)}. That is deliberate: it keeps the whole
   * {@link RetryBackoffSpec} contract — jitter, attempt counting, exhaustion — intact instead of
   * hand-rolling a retry loop, and erring towards waiting slightly longer is safe for a client
   * honouring a throttle. It is never more aggressive than the backoff alone.</p>
   *
   * @param maxRetryAfter longest server-requested delay to honour; beyond it the sequence fails
   *                      immediately instead of waiting
   */
  public static RetryBackoffSpec retry(long maxAttempts, Duration duration, double jitterFactor,
      Duration maxRetryAfter) {
    return Retry.backoff(maxAttempts, duration)
        .jitter(jitterFactor)
        .doBeforeRetryAsync(retrySignal -> retryAfterDelay(retrySignal.failure(), maxRetryAfter))
        .doAfterRetry(retrySignal -> log.warn("Will retry. [Retry count: {}][Retry LocalTime: {}]",
            retrySignal.totalRetries(), LocalTime.now()))
        .filter(WebClientUtil::shouldRetryOn)
        .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> retrySignal.failure());
  }

  public static RetryBackoffSpec retry(long maxAttempts, Duration duration) {
    return retry(maxAttempts, duration, DEFAULT_JITTER_FACTOR);
  }

  /**
   * The extra wait a server's {@code Retry-After} asks for, or an error when it exceeds the bound.
   */
  private static Mono<Void> retryAfterDelay(Throwable failure, Duration maxRetryAfter) {
    Duration retryAfter = failure instanceof OpenTmfClientResponseException e
        ? e.getRetryAfter()
        : null;
    if (retryAfter == null) {
      return Mono.empty();
    }
    if (retryAfter.compareTo(maxRetryAfter) > 0) {
      log.warn("Not retrying: server asked for {} but max-retry-after is {}.",
          retryAfter, maxRetryAfter);
      return Mono.error(failure);
    }
    return Mono.delay(retryAfter).then();
  }

  /**
   * Returns a transform operator that converts a 404 error into an empty {@link Mono}.
   * Use with {@code .transform(WebClientUtil.emptyOn404())}.
   */
  public static <T> Function<Mono<T>, Mono<T>> emptyOn404() {
    return mono -> mono.onErrorResume(
        OpenTmfClientNotFoundException.class, e -> Mono.empty());
  }

  /**
   * Returns a transform operator that converts errors matching any of the given status codes
   * into an empty {@link Mono}. Use with {@code .transform(WebClientUtil.emptyOn(...))}.
   */
  public static <T> Function<Mono<T>, Mono<T>> emptyOn(HttpStatus... statuses) {
    var statusSet = Set.of(statuses);
    return mono -> mono.onErrorResume(OpenTmfClientResponseException.class, e -> {
      if (statusSet.stream().anyMatch(s -> e.getStatusCode().isSameCodeAs(s))) {
        return Mono.empty();
      }
      return Mono.error(e);
    });
  }
}
