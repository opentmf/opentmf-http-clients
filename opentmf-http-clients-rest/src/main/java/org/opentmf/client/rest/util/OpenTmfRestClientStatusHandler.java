package org.opentmf.client.rest.util;

import java.io.IOException;
import lombok.Generated;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.util.ErrorBodyExtractor;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * A {@link RestClient.ResponseSpec.ErrorHandler} that converts HTTP error responses into
 * {@link OpenTmfClientResponseException} (or {@link OpenTmfClientNotFoundException} for 404).
 * Registered automatically on all library-created {@code RestClient} instances via
 * {@link RestClient.Builder#defaultStatusHandler(java.util.function.Predicate,
 * RestClient.ResponseSpec.ErrorHandler)}.
 */
public final class OpenTmfRestClientStatusHandler {

  @Generated
  private OpenTmfRestClientStatusHandler() {
  }

  /**
   * Returns an {@link RestClient.ResponseSpec.ErrorHandler} that maps HTTP errors
   * to {@link OpenTmfClientResponseException} (and 404 to {@link OpenTmfClientNotFoundException}).
   */
  public static RestClient.ResponseSpec.ErrorHandler errorHandler() {
    return OpenTmfRestClientStatusHandler::handleError;
  }

  static void handleError(HttpRequest request, ClientHttpResponse response)
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
