package org.opentmf.client.bearer.exception;

import java.io.Serial;
import lombok.Getter;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.springframework.http.HttpStatusCode;

@Getter
public class BearerWebClientException extends OpenTmfClientResponseException {

  @Serial
  private static final long serialVersionUID = 3L;

  public BearerWebClientException(HttpStatusCode httpStatus) {
    super(httpStatus);
  }

  public BearerWebClientException(HttpStatusCode httpStatusCode, String message) {
    super(httpStatusCode, message);
  }

  public BearerWebClientException(HttpStatusCode httpStatusCode, String message, Throwable cause) {
    super(httpStatusCode, message, cause);
  }
}
