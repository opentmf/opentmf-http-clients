package org.opentmf.client.bearer.exception;

import java.io.Serial;
import java.net.URI;
import lombok.Getter;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.springframework.http.HttpStatusCode;

/**
 * Thrown when the token endpoint answered with a success status, but the answer carries no token
 * the library can use: an empty body, a body that is not a JSON object, or a JSON object whose
 * {@code bearer-auth.token-field} is missing, null, not a string or blank.
 *
 * <p>The message names the token endpoint, the status and the token field — never the body,
 * which may hold other credentials. For the same reason no parser exception is chained as the
 * cause (a parser's message quotes the input) and {@link #getResponseBody()} is {@code null}.</p>
 *
 * <p>{@link #getStatusCode()} is the success status the endpoint answered with, so it is not an
 * error status: map this failure on its type, not on its status. Like a status error from the
 * token endpoint it is an {@link OpenTmfClientResponseException} — the identity provider
 * answered — and it is never retried.</p>
 */
@Getter
public class BearerTokenResponseException extends OpenTmfClientResponseException {

  @Serial
  private static final long serialVersionUID = 1L;

  /** What is wrong with the answer; each names a shape, never a value. */
  public enum Reason {
    EMPTY_BODY("has an empty body"),
    NOT_A_JSON_OBJECT("is not a JSON object"),
    FIELD_MISSING("has no '%s' field"),
    FIELD_NOT_A_STRING("has a '%s' field that is not a string"),
    FIELD_BLANK("has a blank '%s' field");

    private final String description;

    Reason(String description) {
      this.description = description;
    }

    String describe(String tokenField) {
      return description.formatted(tokenField);
    }
  }

  /** The token endpoint that answered. */
  private final URI tokenUrl;

  /** The configured {@code bearer-auth.token-field} the token was expected in. */
  private final String tokenField;

  private final Reason reason;

  public BearerTokenResponseException(URI tokenUrl, HttpStatusCode statusCode, String tokenField,
      Reason reason) {
    super(statusCode, "Bearer token response from " + tokenUrl + " (" + statusCode + ") "
        + reason.describe(tokenField) + "; expected a JSON object with a '" + tokenField
        + "' string");
    this.tokenUrl = tokenUrl;
    this.tokenField = tokenField;
    this.reason = reason;
  }
}
