package org.opentmf.client.rest.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

class RestTemplateUtilTest {

  @Test
  void handleError_withBody() {
    var restEx = new HttpServerErrorException(
        HttpStatus.INTERNAL_SERVER_ERROR, "Server Error",
        "response body".getBytes(), null);

    var ex = RestTemplateUtil.handleError(restEx, OpenTmfClientResponseException.class);
    assertThat(ex).isInstanceOf(OpenTmfClientResponseException.class);
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }

  @Test
  void handleError_emptyBody() {
    var restEx = new HttpServerErrorException(
        HttpStatus.BAD_GATEWAY, "Bad Gateway", new byte[0], null);

    var ex = RestTemplateUtil.handleError(restEx, OpenTmfClientResponseException.class);
    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
  }

  @Test
  void shouldRetryOn_retryableOpenTmfException() {
    var ex = new OpenTmfClientResponseException(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(RestTemplateUtil.shouldRetryOn(ex)).isTrue();
  }

  @Test
  void shouldRetryOn_nonRetryableOpenTmfException() {
    var ex = new OpenTmfClientResponseException(HttpStatus.BAD_REQUEST);
    assertThat(RestTemplateUtil.shouldRetryOn(ex)).isFalse();
  }

  @Test
  void shouldRetryOn_retryableRestClientException() {
    var ex = new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(RestTemplateUtil.shouldRetryOn(ex)).isTrue();
  }

  @Test
  void shouldRetryOn_nonRetryableRestClientException() {
    var ex = new HttpServerErrorException(HttpStatus.UNAUTHORIZED);
    assertThat(RestTemplateUtil.shouldRetryOn(ex)).isFalse();
  }

  @Test
  void shouldRetryOn_nonHttpException() {
    assertThat(RestTemplateUtil.shouldRetryOn(new RuntimeException("network error"))).isFalse();
  }

  @Test
  void executeWithRetry_succeeds_firstAttempt() {
    String result = RestTemplateUtil.executeWithRetry(
        () -> "ok", 3, Duration.ofMillis(1));
    assertThat(result).isEqualTo("ok");
  }

  @Test
  void executeWithRetry_retries_thenSucceeds() {
    var counter = new AtomicInteger(0);
    String result = RestTemplateUtil.executeWithRetry(() -> {
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
    assertThatThrownBy(() -> RestTemplateUtil.executeWithRetry(
        () -> {
          throw new OpenTmfClientResponseException(HttpStatus.SERVICE_UNAVAILABLE);
        }, 2, Duration.ofMillis(1)))
        .isInstanceOf(OpenTmfClientResponseException.class);
  }

  @Test
  void executeWithRetry_nonRetryableError_throwsImmediately() {
    var counter = new AtomicInteger(0);
    assertThatThrownBy(() -> RestTemplateUtil.executeWithRetry(() -> {
      counter.incrementAndGet();
      throw new OpenTmfClientResponseException(HttpStatus.BAD_REQUEST);
    }, 3, Duration.ofMillis(1)))
        .isInstanceOf(OpenTmfClientResponseException.class);
    assertThat(counter.get()).isEqualTo(1);
  }

  @Test
  void executeWithRetry_runnable_succeeds() {
    var counter = new AtomicInteger(0);
    RestTemplateUtil.executeWithRetry(counter::incrementAndGet, 3, Duration.ofMillis(1));
    assertThat(counter.get()).isEqualTo(1);
  }

  @Test
  void executeWithRetry_runnable_retries() {
    var counter = new AtomicInteger(0);
    RestTemplateUtil.executeWithRetry(() -> {
      if (counter.incrementAndGet() <= 1) {
        throw new OpenTmfClientResponseException(HttpStatus.SERVICE_UNAVAILABLE);
      }
    }, 3, Duration.ofMillis(1));
    assertThat(counter.get()).isEqualTo(2);
  }

  @Test
  void executeWithRetry_withJitter() {
    String result = RestTemplateUtil.executeWithRetry(
        () -> "ok", 3, Duration.ofMillis(1), 0.5);
    assertThat(result).isEqualTo("ok");
  }

  @Test
  void emptyOn404_returnsEmpty_onNotFound() {
    Optional<String> result = RestTemplateUtil.emptyOn404(() -> {
      throw new OpenTmfClientNotFoundException(HttpStatus.NOT_FOUND);
    });
    assertThat(result).isEmpty();
  }

  @Test
  void emptyOn404_returnsValue_onSuccess() {
    Optional<String> result = RestTemplateUtil.emptyOn404(() -> "found");
    assertThat(result).contains("found");
  }

  @Test
  void emptyOn404_returnsEmpty_onNullResult() {
    Optional<String> result = RestTemplateUtil.emptyOn404(() -> null);
    assertThat(result).isEmpty();
  }

  @Test
  void emptyOn404_rethrows_otherExceptions() {
    assertThatThrownBy(() -> RestTemplateUtil.emptyOn404(() -> {
      throw new OpenTmfClientResponseException(HttpStatus.BAD_REQUEST);
    })).isInstanceOf(OpenTmfClientResponseException.class);
  }

  @Test
  void emptyOn_returnsEmpty_onMatchingStatus() {
    Optional<String> result = RestTemplateUtil.emptyOn(() -> {
      throw new OpenTmfClientResponseException(HttpStatus.GONE);
    }, HttpStatus.NOT_FOUND, HttpStatus.GONE);
    assertThat(result).isEmpty();
  }

  @Test
  void emptyOn_rethrows_nonMatchingStatus() {
    assertThatThrownBy(() -> RestTemplateUtil.emptyOn(() -> {
      throw new OpenTmfClientResponseException(HttpStatus.BAD_REQUEST);
    }, HttpStatus.NOT_FOUND))
        .isInstanceOf(OpenTmfClientResponseException.class);
  }

  @Test
  void emptyOn_returnsValue_onSuccess() {
    Optional<String> result = RestTemplateUtil.emptyOn(() -> "ok", HttpStatus.NOT_FOUND);
    assertThat(result).contains("ok");
  }
}
