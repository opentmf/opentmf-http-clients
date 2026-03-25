package org.opentmf.client.rest.util;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.util.HttpClientUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientResponseException;

/**
 * Utility methods for synchronous HTTP clients ({@code RestTemplate} and {@code RestClient}).
 *
 * <p>All methods in this class work equally well with both client types because they operate on
 * the shared exception hierarchy ({@link RestClientResponseException},
 * {@link OpenTmfClientResponseException}) rather than on specific client APIs.</p>
 */
@Slf4j
public final class SyncClientUtil {

  private static final double DEFAULT_JITTER_FACTOR = 0.0d;

  private SyncClientUtil() {
  }

  public static OpenTmfClientResponseException handleError(RestClientResponseException ex,
      Class<? extends OpenTmfClientResponseException> exceptionClass) {
    var status = ex.getStatusCode();
    var body = ex.getResponseBodyAsString();
    log.debug("Handling {} {}", status, ex.getStatusText());
    if (body.isEmpty()) {
      return HttpClientUtil.createException(status, exceptionClass);
    }
    return HttpClientUtil.createException(status, body, exceptionClass);
  }

  public static boolean shouldRetryOn(@NonNull Throwable throwable) {
    HttpStatusCode status = null;
    if (throwable instanceof OpenTmfClientResponseException e) {
      status = e.getStatusCode();
    } else if (throwable instanceof RestClientResponseException e) {
      status = e.getStatusCode();
    }
    return HttpClientUtil.isRetryableStatus(status);
  }

  /**
   * Executes the given action with exponential backoff retry on retryable HTTP status codes.
   *
   * @param action       the operation to execute
   * @param maxAttempts  maximum number of retry attempts (not counting the initial call)
   * @param waitDuration base wait duration before the first retry (doubles on each subsequent retry)
   * @param jitterFactor jitter factor between 0.0 (no jitter) and 1.0 (full jitter)
   * @param <T>          return type
   * @return the result of the action
   */
  public static <T> T executeWithRetry(Supplier<T> action, long maxAttempts,
      Duration waitDuration, double jitterFactor) {
    long baseMs = waitDuration.toMillis();
    for (long attempt = 0; ; attempt++) {
      try {
        return action.get();
      } catch (RuntimeException e) {
        if (attempt >= maxAttempts || !shouldRetryOn(e)) {
          throw e;
        }
        long backoffMs = baseMs * (1L << attempt);
        long sleepMs = applyJitter(backoffMs, jitterFactor);
        log.warn("Will retry. [Retry count: {}][Retry LocalTime: {}]", attempt + 1,
            LocalTime.now());
        sleep(sleepMs);
      }
    }
  }

  public static <T> T executeWithRetry(Supplier<T> action, long maxAttempts,
      Duration waitDuration) {
    return executeWithRetry(action, maxAttempts, waitDuration, DEFAULT_JITTER_FACTOR);
  }

  /**
   * Executes the given void action with exponential backoff retry on retryable HTTP status codes.
   */
  public static void executeWithRetry(Runnable action, long maxAttempts,
      Duration waitDuration, double jitterFactor) {
    executeWithRetry(() -> {
      action.run();
      return null;
    }, maxAttempts, waitDuration, jitterFactor);
  }

  public static void executeWithRetry(Runnable action, long maxAttempts,
      Duration waitDuration) {
    executeWithRetry(action, maxAttempts, waitDuration, DEFAULT_JITTER_FACTOR);
  }

  /**
   * Executes the action and returns {@link Optional#empty()} if the response is 404 Not Found
   * or the result is {@code null}. All other errors are rethrown.
   */
  public static <T> Optional<T> emptyOn404(Supplier<T> action) {
    try {
      return Optional.ofNullable(action.get());
    } catch (OpenTmfClientNotFoundException e) {
      return Optional.empty();
    }
  }

  /**
   * Executes the action and returns {@link Optional#empty()} if the response matches any of the
   * given status codes or the result is {@code null}. All other errors are rethrown.
   */
  public static <T> Optional<T> emptyOn(Supplier<T> action, HttpStatus... statuses) {
    var statusSet = Set.of(statuses);
    try {
      return Optional.ofNullable(action.get());
    } catch (OpenTmfClientResponseException e) {
      if (statusSet.stream().anyMatch(s -> e.getStatusCode().isSameCodeAs(s))) {
        return Optional.empty();
      }
      throw e;
    }
  }

  private static long applyJitter(long backoffMs, double jitterFactor) {
    if (jitterFactor <= 0.0d) {
      return backoffMs;
    }
    long jitterRange = (long) (backoffMs * jitterFactor);
    return backoffMs - jitterRange + ThreadLocalRandom.current().nextLong(2 * jitterRange + 1);
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Retry interrupted", e);
    }
  }
}
