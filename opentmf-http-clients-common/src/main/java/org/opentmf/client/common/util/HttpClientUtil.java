package org.opentmf.client.common.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import lombok.Generated;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@Slf4j
public class HttpClientUtil {

  @Generated
  private HttpClientUtil() {
  }

  private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(
      HttpStatus.REQUEST_TIMEOUT.value(),
      HttpStatus.TOO_MANY_REQUESTS.value(),
      HttpStatus.INTERNAL_SERVER_ERROR.value(),
      HttpStatus.BAD_GATEWAY.value(),
      HttpStatus.SERVICE_UNAVAILABLE.value(),
      HttpStatus.GATEWAY_TIMEOUT.value(),
      509 // Bandwidth Limit Exceeded — no non-deprecated HttpStatus constant
  );

  public static boolean isRetryableStatus(HttpStatusCode httpStatusCode) {
    return httpStatusCode != null && RETRYABLE_STATUS_CODES.contains(httpStatusCode.value());
  }

  /**
   * Resolves the {@code Retry-After} this library will act on, which is narrower than the header
   * itself. A {@code Retry-After} on a status {@link #isRetryableStatus} rejects is discarded with
   * a warning: honouring it would mean <em>introducing</em> a retry the caller never opted into,
   * so the header may only reschedule a retry that would have happened anyway.
   *
   * @return the requested delay, or {@code null} when absent, unparseable, non-positive, or
   *         carried by a non-retryable status
   */
  public static @Nullable Duration retryAfterFor(HttpStatusCode status,
      @Nullable HttpHeaders headers) {
    if (headers == null) {
      return null;
    }
    String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
    if (value == null || value.isBlank()) {
      return null;
    }
    Duration retryAfter = parseRetryAfter(value);
    if (retryAfter == null) {
      log.debug("Ignoring unparseable Retry-After '{}'", value);
      return null;
    }
    if (!isRetryableStatus(status)) {
      log.warn("Ignoring Retry-After '{}' on HTTP {}: the status is not retryable, so honouring "
          + "it would introduce a retry that was never requested.", value, status.value());
      return null;
    }
    return retryAfter;
  }

  /**
   * Parses either {@code Retry-After} grammar — {@code delay-seconds} or an HTTP-date. A date in
   * the past (clock skew, or a stale response) yields {@code null} rather than a negative or
   * absurd delay.
   *
   * @return a positive delay, or {@code null} if the value is unparseable or non-positive
   */
  public static @Nullable Duration parseRetryAfter(@Nullable String headerValue) {
    if (headerValue == null || headerValue.isBlank()) {
      return null;
    }
    String trimmed = headerValue.trim();
    Duration parsed;
    try {
      parsed = Duration.ofSeconds(Long.parseLong(trimmed));
    } catch (NumberFormatException e) {
      parsed = parseHttpDate(trimmed);
    }
    return parsed != null && !parsed.isZero() && !parsed.isNegative() ? parsed : null;
  }

  private static @Nullable Duration parseHttpDate(String value) {
    try {
      var date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
      return Duration.between(Instant.now(), date.toInstant());
    } catch (Exception e) {
      return null;
    }
  }

  public static <T extends OpenTmfClientResponseException> T createException(
      HttpStatusCode httpStatusCode, Class<T> exceptionClass) {
    try {
      return exceptionClass.getDeclaredConstructor(HttpStatusCode.class)
          .newInstance(httpStatusCode);
    } catch (Exception e) {
      throw new IllegalArgumentException("Exception class misses required constructor.", e);
    }
  }

  public static <T extends OpenTmfClientResponseException> T createException(
      HttpStatusCode httpStatusCode, String message, Class<T> exceptionClass) {
    try {
      return exceptionClass.getDeclaredConstructor(HttpStatusCode.class, String.class)
          .newInstance(httpStatusCode, message);
    } catch (Exception e) {
      throw new IllegalArgumentException("Exception class misses required constructor.", e);
    }
  }

  /**
   * Remaps an {@link OpenTmfClientResponseException} to a subclass instance. The target class
   * must have a constructor accepting {@code (HttpStatusCode, String, String)} or
   * {@code (HttpStatusCode, String)}. This is intended for consumer libraries that want to
   * convert the auto-wrapped exception into a domain-specific type.
   *
   * <p>Captured response headers and {@code Retry-After} are carried over to the new instance;
   * they would otherwise vanish for consumers using domain-specific exception types.</p>
   */
  public static <T extends OpenTmfClientResponseException> T remap(
      OpenTmfClientResponseException source, Class<T> targetClass) {
    try {
      T remapped = targetClass
          .getDeclaredConstructor(HttpStatusCode.class, String.class, String.class)
          .newInstance(source.getStatusCode(), source.getMessage(), source.getResponseBody());
      remapped.setResponseDetails(source.getHeaders(), source.getRetryAfter());
      return remapped;
    } catch (NoSuchMethodException e) {
      T remapped = createException(source.getStatusCode(), source.getMessage(), targetClass);
      remapped.setResponseDetails(source.getHeaders(), source.getRetryAfter());
      return remapped;
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Cannot remap to " + targetClass.getSimpleName() + ": missing required constructor.", e);
    }
  }
}
