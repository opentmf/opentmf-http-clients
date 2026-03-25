package org.opentmf.client.rest.util;

import java.io.IOException;
import java.net.URI;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.util.ErrorBodyExtractor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

/**
 * A {@link ResponseErrorHandler} that converts HTTP error responses into
 * {@link OpenTmfClientResponseException} (or {@link OpenTmfClientNotFoundException} for 404).
 * Registered automatically on all library-created {@code RestTemplate} instances.
 */
public class OpenTmfResponseErrorHandler implements ResponseErrorHandler {

  @Override
  public boolean hasError(ClientHttpResponse response) throws IOException {
    return response.getStatusCode().isError();
  }

  @Override
  public void handleError(URI url, HttpMethod method, ClientHttpResponse response)
      throws IOException {
    var status = response.getStatusCode();
    byte[] body = response.getBody().readAllBytes();
    String rawBody = ErrorBodyExtractor.decodeAsText(body);
    String message = ErrorBodyExtractor.extractMessage(status, body);

    if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
      throw new OpenTmfClientNotFoundException(status, message, rawBody);
    }
    throw new OpenTmfClientResponseException(status, message, rawBody);
  }
}
