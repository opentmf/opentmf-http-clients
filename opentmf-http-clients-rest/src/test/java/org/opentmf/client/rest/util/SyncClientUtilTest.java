package org.opentmf.client.rest.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

class SyncClientUtilTest {

  @Test
  void handleError_withBody() {
    var restEx = new HttpServerErrorException(
        HttpStatus.INTERNAL_SERVER_ERROR, "Server Error",
        "response body".getBytes(), null);

    var ex = SyncClientUtil.handleError(restEx, OpenTmfClientResponseException.class);
    assertThat(ex).isInstanceOf(OpenTmfClientResponseException.class);
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }

  @Test
  void handleError_emptyBody() {
    var restEx = new HttpServerErrorException(
        HttpStatus.BAD_GATEWAY, "Bad Gateway", new byte[0], null);

    var ex = SyncClientUtil.handleError(restEx, OpenTmfClientResponseException.class);
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
  }

  @Test
  void shouldRetryOn_retryableOpenTmfException() {
    var ex = new OpenTmfClientResponseException(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(SyncClientUtil.shouldRetryOn(ex)).isTrue();
  }

  @Test
  void shouldRetryOn_nonRetryableOpenTmfException() {
    var ex = new OpenTmfClientResponseException(HttpStatus.BAD_REQUEST);
    assertThat(SyncClientUtil.shouldRetryOn(ex)).isFalse();
  }

  @Test
  void shouldRetryOn_retryableRestClientException() {
    var ex = new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(SyncClientUtil.shouldRetryOn(ex)).isTrue();
  }

  @Test
  void shouldRetryOn_nonRetryableRestClientException() {
    var ex = new HttpServerErrorException(HttpStatus.UNAUTHORIZED);
    assertThat(SyncClientUtil.shouldRetryOn(ex)).isFalse();
  }

  @Test
  void shouldRetryOn_nonHttpException() {
    assertThat(SyncClientUtil.shouldRetryOn(new RuntimeException("network error"))).isFalse();
  }

  @Test
  void executeWithRetry_succeeds_firstAttempt() {
    String result = SyncClientUtil.executeWithRetry(
        () -> "ok", 3, Duration.ofMillis(1));
    assertThat(result).isEqualTo("ok");
  }

  @Test
  void executeWithRetry_retries_thenSucceeds() {
    var counter = new AtomicInteger(0);
    String result = SyncClientUtil.executeWithRetry(() -> {
      if (counter.incrementAndGet() <= 2) {
        throw new OpenTmfClientResponseException(HttpStatus.SERVICE_UNAVAILABLE);
      }
      return "recovered";
    }, 3, Duration.ofMillis(1));

    assertThat(result).isEqualTo("recovered");
    assertThat(counter.get()).isEqualTo(3);
  }

  @Test
  void executeWithRetry_exhaustsRetries_throws() {
    var wait = Duration.ofMillis(1);
    Supplier<String> failing = () -> {
      throw new OpenTmfClientResponseException(HttpStatus.SERVICE_UNAVAILABLE);
    };
    assertThatThrownBy(() -> SyncClientUtil.executeWithRetry(failing, 2, wait))
        .isInstanceOf(OpenTmfClientResponseException.class);
  }

  @Test
  void executeWithRetry_nonRetryableError_throwsImmediately() {
    var counter = new AtomicInteger(0);
    var wait = Duration.ofMillis(1);
    Supplier<String> failing = () -> {
      counter.incrementAndGet();
      throw new OpenTmfClientResponseException(HttpStatus.BAD_REQUEST);
    };
    assertThatThrownBy(() -> SyncClientUtil.executeWithRetry(failing, 3, wait))
        .isInstanceOf(OpenTmfClientResponseException.class);
    assertThat(counter.get()).isEqualTo(1);
  }

  @Test
  void executeWithRetry_runnable_succeeds() {
    var counter = new AtomicInteger(0);
    SyncClientUtil.executeWithRetry(counter::incrementAndGet, 3, Duration.ofMillis(1));
    assertThat(counter.get()).isEqualTo(1);
  }

  @Test
  void executeWithRetry_runnable_retries() {
    var counter = new AtomicInteger(0);
    SyncClientUtil.executeWithRetry(() -> {
      if (counter.incrementAndGet() <= 1) {
        throw new OpenTmfClientResponseException(HttpStatus.SERVICE_UNAVAILABLE);
      }
    }, 3, Duration.ofMillis(1));
    assertThat(counter.get()).isEqualTo(2);
  }

  @Test
  void executeWithRetry_withJitter() {
    String result = SyncClientUtil.executeWithRetry(
        () -> "ok", 3, Duration.ofMillis(1), 0.5);
    assertThat(result).isEqualTo("ok");
  }

  @Test
  void emptyOn404_returnsEmpty_onNotFound() {
    Optional<String> result = SyncClientUtil.emptyOn404(() -> {
      throw new OpenTmfClientNotFoundException(HttpStatus.NOT_FOUND);
    });
    assertThat(result).isEmpty();
  }

  @Test
  void emptyOn404_returnsValue_onSuccess() {
    Optional<String> result = SyncClientUtil.emptyOn404(() -> "found");
    assertThat(result).contains("found");
  }

  @Test
  void emptyOn404_returnsEmpty_onNullResult() {
    Optional<String> result = SyncClientUtil.emptyOn404(() -> null);
    assertThat(result).isEmpty();
  }

  @Test
  void emptyOn404_rethrows_otherExceptions() {
    assertThatThrownBy(() -> SyncClientUtil.emptyOn404(() -> {
      throw new OpenTmfClientResponseException(HttpStatus.BAD_REQUEST);
    })).isInstanceOf(OpenTmfClientResponseException.class);
  }

  @Test
  void emptyOn_returnsEmpty_onMatchingStatus() {
    Optional<String> result = SyncClientUtil.emptyOn(() -> {
      throw new OpenTmfClientResponseException(HttpStatus.GONE);
    }, HttpStatus.NOT_FOUND, HttpStatus.GONE);
    assertThat(result).isEmpty();
  }

  @Test
  void emptyOn_rethrows_nonMatchingStatus() {
    assertThatThrownBy(() -> SyncClientUtil.emptyOn(() -> {
      throw new OpenTmfClientResponseException(HttpStatus.BAD_REQUEST);
    }, HttpStatus.NOT_FOUND))
        .isInstanceOf(OpenTmfClientResponseException.class);
  }

  @Test
  void emptyOn_returnsValue_onSuccess() {
    Optional<String> result = SyncClientUtil.emptyOn(() -> "ok", HttpStatus.NOT_FOUND);
    assertThat(result).contains("ok");
  }
}
