package org.opentmf.client.reactive.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Reactive counterpart of {@code SyncClientUtilRetryAfterTest}. The requested delay is applied in
 * addition to the spec's own backoff (see {@link WebClientUtil#retry}), so assertions bound the
 * elapsed time from below rather than pinning it exactly.
 */
class WebClientUtilRetryAfterTest {

  private static OpenTmfClientResponseException unavailable(Duration retryAfter) {
    var ex = new OpenTmfClientResponseException(HttpStatus.SERVICE_UNAVAILABLE, "unavailable");
    ex.setResponseDetails(null, retryAfter);
    return ex;
  }

  @Test
  void retryAfterLengthensTheWait() {
    var calls = new AtomicInteger();
    Mono<String> mono = Mono.defer(() -> calls.incrementAndGet() < 2
            ? Mono.error(unavailable(Duration.ofMillis(300)))
            : Mono.just("ok"))
        .retryWhen(WebClientUtil.retry(3, Duration.ofMillis(10), 0.0d, Duration.ofSeconds(30)));

    long start = System.nanoTime();
    StepVerifier.create(mono).expectNext("ok").verifyComplete();
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(calls).hasValue(2);
    assertThat(elapsedMs).isGreaterThanOrEqualTo(300);
  }

  @Test
  void retryAfterBeyondTheCap_failsFastWithoutWaiting() {
    var calls = new AtomicInteger();
    Mono<String> mono = Mono.<String>defer(() -> {
          calls.incrementAndGet();
          return Mono.error(unavailable(Duration.ofHours(1)));
        })
        .retryWhen(WebClientUtil.retry(3, Duration.ofMillis(10), 0.0d, Duration.ofSeconds(30)));

    long start = System.nanoTime();
    StepVerifier.create(mono)
        .expectErrorSatisfies(t -> assertThat(t)
            .isInstanceOf(OpenTmfClientResponseException.class))
        .verify(Duration.ofSeconds(5));
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(calls).hasValue(1);
    assertThat(elapsedMs).isLessThan(3_000);
  }

  @Test
  void retryAfterNeverIncreasesTheAttemptBudget() {
    var calls = new AtomicInteger();
    Mono<String> mono = Mono.<String>defer(() -> {
          calls.incrementAndGet();
          return Mono.error(unavailable(Duration.ofMillis(1)));
        })
        .retryWhen(WebClientUtil.retry(2, Duration.ofMillis(1), 0.0d, Duration.ofSeconds(30)));

    StepVerifier.create(mono)
        .expectError(OpenTmfClientResponseException.class)
        .verify(Duration.ofSeconds(5));

    assertThat(calls).hasValue(3);
  }

  @Test
  void withoutRetryAfter_behaviourIsUnchanged() {
    var calls = new AtomicInteger();
    Mono<String> mono = Mono.defer(() -> calls.incrementAndGet() < 2
            ? Mono.error(new OpenTmfClientResponseException(
                HttpStatus.SERVICE_UNAVAILABLE, "boom"))
            : Mono.just("ok"))
        .retryWhen(WebClientUtil.retry(3, Duration.ofMillis(20)));

    StepVerifier.create(mono).expectNext("ok").verifyComplete();

    assertThat(calls).hasValue(2);
  }

  @Test
  void nonRetryableStatus_isNotRetried_evenWithRetryAfter() {
    var calls = new AtomicInteger();
    var forbidden = new OpenTmfClientResponseException(HttpStatus.FORBIDDEN, "denied");
    forbidden.setResponseDetails(null, Duration.ofMillis(10));

    Mono<String> mono = Mono.<String>defer(() -> {
          calls.incrementAndGet();
          return Mono.error(forbidden);
        })
        .retryWhen(WebClientUtil.retry(3, Duration.ofMillis(10), 0.0d, Duration.ofSeconds(30)));

    StepVerifier.create(mono)
        .expectError(OpenTmfClientResponseException.class)
        .verify(Duration.ofSeconds(5));

    assertThat(calls).hasValue(1);
  }

  @Test
  void retryStillReturnsARetryBackoffSpec() {
    // Public API shape: callers may declare the variable, so the type must not narrow to Retry.
    assertThat(WebClientUtil.retry(1, Duration.ofMillis(1), 0.0d, Duration.ofSeconds(30)))
        .isNotNull();
  }
}
