package org.opentmf.client.rest.util;

import java.io.IOException;
import lombok.Generated;
import org.jspecify.annotations.Nullable;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.model.PeerTextInMessage;
import org.opentmf.client.common.util.ErrorBodyExtractor;
import org.opentmf.client.common.util.HttpClientUtil;
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
    return errorHandler(PeerTextInMessage.WHOLE);
  }

  /**
   * As {@link #errorHandler()}, its messages carrying as much of the peer's text as
   * {@code peerText} allows.
   *
   * @param peerText the client's {@code peer-text-in-message}; {@code null} means
   *     {@link PeerTextInMessage#WHOLE}
   */
  public static RestClient.ResponseSpec.ErrorHandler errorHandler(
      @Nullable PeerTextInMessage peerText) {
    return (request, response) -> handleError(response, peerText);
  }

  static void handleError(HttpRequest request, ClientHttpResponse response)
      throws IOException {
    handleError(response, PeerTextInMessage.WHOLE);
  }

  private static void handleError(ClientHttpResponse response,
      @Nullable PeerTextInMessage peerText) throws IOException {
    var status = response.getStatusCode();
    var headers = response.getHeaders();
    byte[] body = response.getBody().readAllBytes();
    var contentType = ErrorBodyExtractor.contentTypeOf(headers);
    String rawBody = ErrorBodyExtractor.decodeAsText(body,
        ErrorBodyExtractor.charsetOf(contentType));
    String message = ErrorBodyExtractor.extractMessage(status, body, contentType, peerText);

    OpenTmfClientResponseException ex = status.isSameCodeAs(HttpStatus.NOT_FOUND)
        ? new OpenTmfClientNotFoundException(status, message, rawBody)
        : new OpenTmfClientResponseException(status, message, rawBody);
    ex.setResponseDetails(headers, HttpClientUtil.retryAfterFor(status, headers));
    throw ex;
  }
}
