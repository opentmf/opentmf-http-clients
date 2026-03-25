package org.opentmf.client.common.util;

import java.util.Set;
import lombok.Generated;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

public class HttpClientUtil {

  @Generated
  private HttpClientUtil() {
  }

  private static final Set<HttpStatus> RETRYABLE_STATUS_CODES = Set.of(
      HttpStatus.REQUEST_TIMEOUT,
      HttpStatus.TOO_MANY_REQUESTS,
      HttpStatus.INTERNAL_SERVER_ERROR,
      HttpStatus.BAD_GATEWAY,
      HttpStatus.SERVICE_UNAVAILABLE,
      HttpStatus.GATEWAY_TIMEOUT,
      HttpStatus.BANDWIDTH_LIMIT_EXCEEDED
  );

  public static boolean isRetryableStatus(HttpStatusCode httpStatusCode) {
    return httpStatusCode != null
        && RETRYABLE_STATUS_CODES.contains(HttpStatus.resolve(httpStatusCode.value()));
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
   */
  public static <T extends OpenTmfClientResponseException> T remap(
      OpenTmfClientResponseException source, Class<T> targetClass) {
    try {
      return targetClass
          .getDeclaredConstructor(HttpStatusCode.class, String.class, String.class)
          .newInstance(source.getStatusCode(), source.getMessage(), source.getResponseBody());
    } catch (NoSuchMethodException e) {
      return createException(source.getStatusCode(), source.getMessage(), targetClass);
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Cannot remap to " + targetClass.getSimpleName() + ": missing required constructor.", e);
    }
  }
}
