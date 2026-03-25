package org.opentmf.client.reactive.util;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Set;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.util.HttpClientUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.lang.NonNull;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

@Slf4j
public final class WebClientUtil {

  private static final double DEFAULT_JITTER_FACTOR = 0.0d;

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
    return Retry.backoff(maxAttempts, duration)
        .jitter(jitterFactor)
        .doAfterRetry(retrySignal -> log.warn("Will retry. [Retry count: {}][Retry LocalTime: {}]",
            retrySignal.totalRetries(), LocalTime.now()))
        .filter(WebClientUtil::shouldRetryOn)
        .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> retrySignal.failure());
  }

  public static RetryBackoffSpec retry(long maxAttempts, Duration duration) {
    return retry(maxAttempts, duration, DEFAULT_JITTER_FACTOR);
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
