package org.opentmf.client.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * Covers {@link HttpClientUtil#parseRetryAfter} and {@link HttpClientUtil#retryAfterFor}, the
 * single place a server's {@code Retry-After} enters this library.
 */
class RetryAfterParsingTest {

  @Test
  void parseRetryAfter_delaySeconds() {
    assertThat(HttpClientUtil.parseRetryAfter("120")).isEqualTo(Duration.ofSeconds(120));
  }

  @Test
  void parseRetryAfter_delaySeconds_tolerates_surroundingWhitespace() {
    assertThat(HttpClientUtil.parseRetryAfter("  7 ")).isEqualTo(Duration.ofSeconds(7));
  }

  @Test
  void parseRetryAfter_httpDate_inTheFuture() {
    String future = ZonedDateTime.now().plusMinutes(2)
        .format(DateTimeFormatter.RFC_1123_DATE_TIME);

    var parsed = HttpClientUtil.parseRetryAfter(future);

    assertThat(parsed).isNotNull().isBetween(Duration.ofSeconds(90), Duration.ofSeconds(130));
  }

  @Test
  void parseRetryAfter_httpDate_inThePast_isNull() {
    // Clock skew or a stale response must not yield a negative or absurd delay.
    String past = ZonedDateTime.now().minusHours(1)
        .format(DateTimeFormatter.RFC_1123_DATE_TIME);

    assertThat(HttpClientUtil.parseRetryAfter(past)).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-5", "soon", "", "   ", "12.5", "Tue, 99 Xxx 2026 99:99:99 GMT"})
  void parseRetryAfter_returnsNull_forUnusableValues(String value) {
    assertThat(HttpClientUtil.parseRetryAfter(value)).isNull();
  }

  @Test
  void parseRetryAfter_returnsNull_forNull() {
    assertThat(HttpClientUtil.parseRetryAfter(null)).isNull();
  }

  @Test
  void retryAfterFor_retryableStatus_returnsDelay() {
    var headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, "30");

    assertThat(HttpClientUtil.retryAfterFor(HttpStatus.SERVICE_UNAVAILABLE, headers))
        .isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void retryAfterFor_nonRetryableStatus_isIgnored() {
    // Honouring it would introduce a retry the caller never opted into.
    var headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, "30");

    assertThat(HttpClientUtil.retryAfterFor(HttpStatus.FORBIDDEN, headers)).isNull();
  }

  @Test
  void retryAfterFor_unparseableValue_isIgnored() {
    var headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, "whenever");

    assertThat(HttpClientUtil.retryAfterFor(HttpStatus.SERVICE_UNAVAILABLE, headers)).isNull();
  }

  @Test
  void retryAfterFor_headerAbsent_isNull() {
    assertThat(HttpClientUtil.retryAfterFor(HttpStatus.SERVICE_UNAVAILABLE, new HttpHeaders()))
        .isNull();
  }

  @Test
  void retryAfterFor_nullHeaders_isNull() {
    assertThat(HttpClientUtil.retryAfterFor(HttpStatus.SERVICE_UNAVAILABLE, null)).isNull();
  }

  @Test
  void retryAfterFor_blankHeader_isNull() {
    var headers = new HttpHeaders();
    headers.set(HttpHeaders.RETRY_AFTER, "  ");

    assertThat(HttpClientUtil.retryAfterFor(HttpStatus.TOO_MANY_REQUESTS, headers)).isNull();
  }
}
