package org.opentmf.client.common.exception;

import java.io.Serial;
import lombok.Getter;
import org.springframework.http.HttpStatusCode;

@Getter
public class OpenTmfClientResponseException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 4L;

  private final HttpStatusCode statusCode;
  private final String responseBody;

  public OpenTmfClientResponseException(HttpStatusCode statusCode) {
    this.statusCode = statusCode;
    this.responseBody = null;
  }

  public OpenTmfClientResponseException(HttpStatusCode statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
    this.responseBody = null;
  }

  public OpenTmfClientResponseException(HttpStatusCode statusCode, String message,
      String responseBody) {
    super(message);
    this.statusCode = statusCode;
    this.responseBody = responseBody;
  }

  public OpenTmfClientResponseException(HttpStatusCode statusCode, String message,
      Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
    this.responseBody = null;
  }

  public final int getRawStatusCode() {
    return statusCode.value();
  }
}
