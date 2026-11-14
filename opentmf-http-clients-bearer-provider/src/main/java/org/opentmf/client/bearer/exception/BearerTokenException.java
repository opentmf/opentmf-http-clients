package org.opentmf.client.bearer.exception;

import java.io.Serial;
import java.net.URI;
import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Thrown when the token endpoint answered with an error status (any 4xx or 5xx, a 404 included).
 * A success status without a usable token is its sibling {@link BearerTokenResponseException};
 * together the two are every token call that got an answer and failed.
 *
 * <p>It is an {@link OpenTmfClientResponseException}, so a catch of that type behaves as for any
 * other answered call; catch this type (and {@link BearerTokenResponseException}) first to tell a
 * failed token call from a failed call to the service the token was for. A token endpoint
 * answering 404 is this type, not an {@code OpenTmfClientNotFoundException}: it says the token
 * URL is wrong, not that the resource the caller asked for is absent.</p>
 *
 * <p>The identity provider's response body is withheld: {@link #getResponseBody()} is
 * {@code null} and the message never quotes it, because a provider may echo the request form,
 * which can carry the client secret, a password or a refresh token. The message names the token
 * endpoint, the status and, when the body carries one, the OAuth 2.0 error code
 * (RFC 6749 §5.2, e.g. {@code invalid_client}), also available as {@link #getOauthError()}. The
 * status, the response headers and a parsed {@code Retry-After} are kept, so a retryable status
 * is retried exactly as any other.</p>
 */
@Getter
public class BearerTokenException extends OpenTmfClientResponseException {

  @Serial
  private static final long serialVersionUID = 4L;

  /** The token endpoint that answered; {@code null} on the constructors without one. */
  private final @Nullable URI tokenUrl;

  /**
   * The OAuth 2.0 error code of the answer (RFC 6749 §5.2 {@code error}, e.g.
   * {@code invalid_client}), or {@code null} when the answer carries none the library accepts: a
   * lower-case code of letters, digits and underscores, at most 64 characters long. The rest of
   * the body, {@code error_description} included, is never kept.
   */
  private final @Nullable String oauthError;

  public BearerTokenException(HttpStatusCode httpStatus) {
    super(httpStatus);
    this.tokenUrl = null;
    this.oauthError = null;
  }

  public BearerTokenException(HttpStatusCode httpStatusCode, String message) {
    super(httpStatusCode, message);
    this.tokenUrl = null;
    this.oauthError = null;
  }

  public BearerTokenException(HttpStatusCode httpStatusCode, String message, Throwable cause) {
    super(httpStatusCode, message, cause);
    this.tokenUrl = null;
    this.oauthError = null;
  }

  /**
   * A status failure of the token call: the token endpoint answered {@code statusCode}.
   *
   * @param oauthError the answer's OAuth 2.0 error code, or {@code null}
   */
  public BearerTokenException(URI tokenUrl, HttpStatusCode statusCode,
      @Nullable String oauthError) {
    super(statusCode, "Bearer token request to " + tokenUrl + " answered " + describe(statusCode)
        + (oauthError == null ? "" : " (error=" + oauthError + ")"));
    this.tokenUrl = tokenUrl;
    this.oauthError = oauthError;
  }

  /** {@code 400 Bad Request}; a status without a reason phrase is its number alone. */
  static String describe(HttpStatusCode statusCode) {
    var known = HttpStatus.resolve(statusCode.value());
    return known == null
        ? String.valueOf(statusCode.value())
        : statusCode.value() + " " + known.getReasonPhrase();
  }
}
