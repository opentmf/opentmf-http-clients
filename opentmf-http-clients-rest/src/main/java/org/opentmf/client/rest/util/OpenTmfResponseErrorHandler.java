package org.opentmf.client.rest.util;

import java.io.IOException;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.model.PeerTextInMessage;
import org.opentmf.client.common.util.ErrorBodyExtractor;
import org.opentmf.client.common.util.HttpClientUtil;
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

  private final @Nullable PeerTextInMessage peerText;
  private final int capLength;

  /**
   * A handler whose messages carry the peer's text whole and cut a non-JSON body at
   * {@link ErrorBodyExtractor#MAX_BODY_LENGTH}, as in earlier releases. It ignores every client
   * setting; {@link #OpenTmfResponseErrorHandler(ClientProperties)} follows them.
   */
  public OpenTmfResponseErrorHandler() {
    this(PeerTextInMessage.WHOLE);
  }

  /**
   * A handler whose messages carry as much of the peer's text as {@code peerText} allows, cut
   * at {@link ErrorBodyExtractor#MAX_BODY_LENGTH} where they cut.
   *
   * @param peerText the client's {@code peer-text-in-message}; {@code null} means
   *     {@link PeerTextInMessage#WHOLE}
   */
  public OpenTmfResponseErrorHandler(@Nullable PeerTextInMessage peerText) {
    this.peerText = peerText;
    this.capLength = ErrorBodyExtractor.MAX_BODY_LENGTH;
  }

  /**
   * A handler following the client's {@code peer-text-in-message} and
   * {@code peer-text-cap-length}; the library installs this one on the clients it builds.
   *
   * @throws IllegalArgumentException when the cap length is outside its bounds
   */
  public OpenTmfResponseErrorHandler(ClientProperties properties) {
    this.peerText = properties.getPeerTextInMessage();
    this.capLength = ErrorBodyExtractor.requireCapLength(properties.getPeerTextCapLength());
  }

  @Override
  public boolean hasError(ClientHttpResponse response) throws IOException {
    return response.getStatusCode().isError();
  }

  @Override
  public void handleError(URI url, HttpMethod method, ClientHttpResponse response)
      throws IOException {
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
