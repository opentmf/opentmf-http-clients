package org.opentmf.client.rest.util;

import java.io.IOException;
import lombok.Generated;
import org.jspecify.annotations.Nullable;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.model.PeerTextInMessage;
import org.opentmf.client.common.util.ErrorBodyExtractor;
import org.opentmf.client.common.util.HttpClientUtil;
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
   * Its messages carry the peer's text whole and cut a non-JSON body at
   * {@link ErrorBodyExtractor#MAX_BODY_LENGTH}, as in earlier releases; it ignores every client
   * setting, which {@link #errorHandler(ClientProperties)} follows.
   */
  public static RestClient.ResponseSpec.ErrorHandler errorHandler() {
    return errorHandler(PeerTextInMessage.WHOLE);
  }

  /**
   * As {@link #errorHandler()}, its messages carrying as much of the peer's text as
   * {@code peerText} allows, cut at {@link ErrorBodyExtractor#MAX_BODY_LENGTH} where they cut.
   *
   * @param peerText the client's {@code peer-text-in-message}; {@code null} means
   *     {@link PeerTextInMessage#WHOLE}
   */
  public static RestClient.ResponseSpec.ErrorHandler errorHandler(
      @Nullable PeerTextInMessage peerText) {
    return (request, response) -> handleError(response, peerText,
        ErrorBodyExtractor.MAX_BODY_LENGTH);
  }

  /**
   * As {@link #errorHandler()}, following the client's {@code peer-text-in-message} and
   * {@code peer-text-cap-length}; the library installs this one on the clients it builds.
   *
   * @throws IllegalArgumentException when the cap length is outside its bounds
   */
  public static RestClient.ResponseSpec.ErrorHandler errorHandler(ClientProperties properties) {
    var peerText = properties.getPeerTextInMessage();
    int capLength = ErrorBodyExtractor.requireCapLength(properties.getPeerTextCapLength());
    return (request, response) -> handleError(response, peerText, capLength);
  }

  private static void handleError(ClientHttpResponse response,
      @Nullable PeerTextInMessage peerText, int capLength) throws IOException {
    var status = response.getStatusCode();
    var headers = response.getHeaders();
    byte[] body = response.getBody().readAllBytes();
    var contentType = ErrorBodyExtractor.contentTypeOf(headers);
    String rawBody = ErrorBodyExtractor.decodeAsText(body,
        ErrorBodyExtractor.charsetOf(contentType));
    String message = ErrorBodyExtractor.extractMessage(status, body, contentType, peerText,
        capLength);

    OpenTmfClientResponseException ex = status.isSameCodeAs(HttpStatus.NOT_FOUND)
        ? new OpenTmfClientNotFoundException(status, message, rawBody)
        : new OpenTmfClientResponseException(status, message, rawBody);
    ex.setResponseDetails(headers, HttpClientUtil.retryAfterFor(status, headers));
    throw ex;
  }
}
