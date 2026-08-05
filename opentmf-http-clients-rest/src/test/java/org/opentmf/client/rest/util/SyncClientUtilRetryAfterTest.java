package org.opentmf.client.rest.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.springframework.http.HttpStatus;

/**
 * Covers how {@code Retry-After} interacts with the caller's own retry configuration in
 * {@link SyncClientUtil#executeWithRetry}: the server controls <em>when</em>, the caller controls
 * <em>how many</em> and <em>at most how long</em>.
 */
class SyncClientUtilRetryAfterTest {

  private static final Duration TEN_MS = Duration.ofMillis(10);
  private static final Duration ONE_MS = Duration.ofMillis(1);
  private static final Duration CAP = Duration.ofSeconds(30);

  private static OpenTmfClientResponseException unavailable(Duration retryAfter) {
    var ex = new OpenTmfClientResponseException(HttpStatus.SERVICE_UNAVAILABLE, "unavailable");
    ex.setResponseDetails(null, retryAfter);
    return ex;
  }

  /** Fails with the given exception until the attempt count reaches {@code succeedOnAttempt}. */
  private static Supplier<String> failingUntil(AtomicInteger calls, int succeedOnAttempt,
      Supplier<RuntimeException> failure) {
    return () -> {
      if (calls.incrementAndGet() < succeedOnAttempt) {
        throw failure.get();
      }
      return "ok";
    };
  }

  @Test
  void retryAfterActsAsAFloor_onAShorterBackoff() {
    var calls = new AtomicInteger();
    var action = failingUntil(calls, 2, () -> unavailable(Duration.ofMillis(400)));

    long start = System.nanoTime();
    var result = SyncClientUtil.executeWithRetry(action, 3, Duration.ofMillis(10), 0.0d,
        Duration.ofSeconds(30));
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(result).isEqualTo("ok");
    assertThat(calls).hasValue(2);
    // 10ms backoff raised to the server's 400ms.
    assertThat(elapsedMs).isGreaterThanOrEqualTo(400);
  }

  @Test
  void retryAfterNeverShortensAlongerBackoff() {
    var calls = new AtomicInteger();
    var action = failingUntil(calls, 2, () -> unavailable(Duration.ofMillis(1)));

    long start = System.nanoTime();
    SyncClientUtil.executeWithRetry(action, 3, Duration.ofMillis(300), 0.0d,
        Duration.ofSeconds(30));
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    // A server asking for 1ms must not pull us below our own 300ms backoff.
    assertThat(elapsedMs).isGreaterThanOrEqualTo(300);
  }

  @Test
  void retryAfterBeyondTheCap_failsFastWithoutWaiting() {
    var calls = new AtomicInteger();
    Supplier<String> action = () -> {
      calls.incrementAndGet();
      throw unavailable(Duration.ofHours(1));
    };

    long start = System.nanoTime();
    assertThatThrownBy(() -> SyncClientUtil.executeWithRetry(action, 3, TEN_MS, 0.0d, CAP))
        .isInstanceOf(OpenTmfClientResponseException.class);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    // No retry, and above all no hour-long thread park.
    assertThat(calls).hasValue(1);
    assertThat(elapsedMs).isLessThan(1_000);
  }

  @Test
  void retryAfterNeverIncreasesTheAttemptBudget() {
    var calls = new AtomicInteger();
    Supplier<String> action = () -> {
      calls.incrementAndGet();
      throw unavailable(Duration.ofMillis(1));
    };

    assertThatThrownBy(() -> SyncClientUtil.executeWithRetry(action, 2, ONE_MS, 0.0d, CAP))
        .isInstanceOf(OpenTmfClientResponseException.class);

    // maxAttempts=2 means the initial call plus two retries, regardless of Retry-After.
    assertThat(calls).hasValue(3);
  }

  @Test
  void withoutRetryAfter_backoffIsUnchanged() {
    var calls = new AtomicInteger();
    var action = failingUntil(calls, 2,
        () -> new OpenTmfClientResponseException(HttpStatus.SERVICE_UNAVAILABLE, "boom"));

    long start = System.nanoTime();
    var result = SyncClientUtil.executeWithRetry(action, 3, Duration.ofMillis(50));
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(result).isEqualTo("ok");
    assertThat(elapsedMs).isGreaterThanOrEqualTo(50).isLessThan(2_000);
  }

  @Test
  void defaultOverloadsApplyTheDefaultCap() {
    var calls = new AtomicInteger();
    Supplier<String> action = () -> {
      calls.incrementAndGet();
      throw unavailable(SyncClientUtil.DEFAULT_MAX_RETRY_AFTER.plusSeconds(1));
    };

    // A caller who never configures a cap is still protected from an unbounded wait.
    assertThatThrownBy(() -> SyncClientUtil.executeWithRetry(action, 3, TEN_MS))
        .isInstanceOf(OpenTmfClientResponseException.class);
    assertThat(calls).hasValue(1);
  }

  @Test
  void retryAfterExactlyAtTheCap_isHonoured() {
    var calls = new AtomicInteger();
    var action = failingUntil(calls, 2, () -> unavailable(Duration.ofMillis(200)));

    var result = SyncClientUtil.executeWithRetry(action, 3, Duration.ofMillis(10), 0.0d,
        Duration.ofMillis(200));

    assertThat(result).isEqualTo("ok");
    assertThat(calls).hasValue(2);
  }

  @Test
  void largeAttemptCountsDoNotOverflowTheBackoffShift() {
    // baseMs * (1L << attempt) is undefined once attempt reaches 63; the shift must be bounded.
    var calls = new AtomicInteger();
    Supplier<String> action = () -> {
      calls.incrementAndGet();
      throw new OpenTmfClientResponseException(HttpStatus.BAD_REQUEST, "no retry");
    };

    assertThatThrownBy(() -> SyncClientUtil.executeWithRetry(action, 100, ONE_MS))
        .isInstanceOf(OpenTmfClientResponseException.class);
    assertThat(calls).hasValue(1);
  }
}
