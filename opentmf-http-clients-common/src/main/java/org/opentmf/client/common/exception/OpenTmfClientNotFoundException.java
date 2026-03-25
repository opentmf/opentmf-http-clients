package org.opentmf.client.common.exception;

import java.io.Serial;
import org.springframework.http.HttpStatusCode;

/**
 * Thrown when an HTTP request receives a 404 Not Found response. This is a subclass of
 * {@link OpenTmfClientResponseException} that allows callers to catch "not found" conditions
 * specifically — for example, to return {@code Optional.empty()} on a GET-by-ID call.
 */
public class OpenTmfClientNotFoundException extends OpenTmfClientResponseException {

  @Serial
  private static final long serialVersionUID = 1L;

  public OpenTmfClientNotFoundException(HttpStatusCode statusCode) {
    super(statusCode);
  }

  public OpenTmfClientNotFoundException(HttpStatusCode statusCode, String message) {
    super(statusCode, message);
  }

  public OpenTmfClientNotFoundException(HttpStatusCode statusCode, String message,
      String responseBody) {
    super(statusCode, message, responseBody);
  }
}
