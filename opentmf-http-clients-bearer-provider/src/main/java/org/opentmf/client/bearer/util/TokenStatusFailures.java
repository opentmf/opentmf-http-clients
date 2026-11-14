package org.opentmf.client.bearer.util;

import java.net.URI;
import java.time.Duration;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.opentmf.client.bearer.exception.BearerTokenException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.commons.util.JacksonUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.StringUtils;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns an error status from the token endpoint into the token call's own failure, a
 * {@link BearerTokenException}: the same status, response headers and {@code Retry-After}, so
 * every retry decision stays as it is, but the identity provider's body withheld (it may echo the
 * request form) save for its OAuth 2.0 error code.
 */
public final class TokenStatusFailures {

  /**
   * The OAuth 2.0 error codes the library keeps: lower-case letters, digits and underscores, at
   * most 64 characters. Every code registered for the token endpoint (RFC 6749 §5.2 and its
   * extensions, e.g. {@code invalid_client}, {@code invalid_grant}, {@code slow_down}) has this
   * shape; free text and anything resembling a credential mostly does not.
   */
  private static final Pattern OAUTH_ERROR = Pattern.compile("[a-z][a-z0-9_]{0,63}");

  /** The answer's field that carries the OAuth 2.0 error code (RFC 6749 §5.2). */
  private static final String ERROR_FIELD = "error";

  private TokenStatusFailures() {
  }

  /**
   * The token call's own failure for a status error the client's status handling raised; a
   * failure that already is a {@link BearerTokenException} is returned as it is. The original
   * exception is not chained as the cause: it carries the body.
   */
  public static OpenTmfClientResponseException toTokenFailure(URI tokenUrl,
      OpenTmfClientResponseException failure) {
    if (failure instanceof BearerTokenException) {
      return failure;
    }
    return of(tokenUrl, failure.getStatusCode(), failure.getHeaders(), failure.getRetryAfter(),
        failure.getResponseBody());
  }

  /**
   * A token call's status failure from the parts of the answer.
   *
   * @param body the answer's body, read for its OAuth 2.0 error code only; never kept
   */
  public static BearerTokenException of(URI tokenUrl, HttpStatusCode status,
      @Nullable HttpHeaders headers, @Nullable Duration retryAfter, @Nullable String body) {
    var failure = new BearerTokenException(tokenUrl, status, oauthErrorOf(body));
    failure.setResponseDetails(headers, retryAfter);
    return failure;
  }

  /** The {@code error} of a JSON object answer, when it has the shape of an OAuth error code. */
  static @Nullable String oauthErrorOf(@Nullable String body) {
    if (!StringUtils.hasText(body)) {
      return null;
    }
    try {
      if (!(JacksonUtil.jsonToTree(body) instanceof ObjectNode answer)) {
        return null;
      }
      var error = answer.get(ERROR_FIELD);
      if (error == null || !error.isString()) {
        return null;
      }
      var code = error.stringValue();
      return OAUTH_ERROR.matcher(code).matches() ? code : null;
    } catch (IllegalArgumentException notJson) {
      // not chained, not logged: the parser's message quotes the body
      return null;
    }
  }
}
