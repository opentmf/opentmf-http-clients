package org.opentmf.client.common.util;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Recognises an API's refusal of a bearer token as invalid (RFC 6750, section 3.1): a {@code 401}
 * whose {@code WWW-Authenticate} carries a {@code Bearer} challenge with
 * {@code error="invalid_token"}. The token is the one the refused request sent in its
 * {@code Authorization: Bearer <token>} header. A {@code 401} without that marker, a {@code 403}
 * and any other answer are not such a refusal.
 *
 * @since 2.6.0
 */
public final class InvalidTokenRefusals {

  private static final int UNAUTHORIZED = 401;

  private static final String BEARER = "bearer ";

  private static final String SCHEME = "bearer";

  private static final String MARKER = ",error=invalid_token,";

  private InvalidTokenRefusals() {
  }

  /**
   * The bearer token an API refused as invalid, or empty when the answer is not such a refusal.
   *
   * @param status        the answer's status code
   * @param challenges    the answer's {@code WWW-Authenticate} values, {@code null} when none
   * @param authorization the refused request's {@code Authorization} value, {@code null} when none
   */
  public static Optional<String> refusedToken(int status, @Nullable List<String> challenges,
      @Nullable String authorization) {
    if (status != UNAUTHORIZED || challenges == null || authorization == null
        || !authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      return Optional.empty();
    }
    var token = authorization.substring(BEARER.length()).trim();
    if (token.isEmpty() || challenges.stream().noneMatch(InvalidTokenRefusals::marksInvalid)) {
      return Optional.empty();
    }
    return Optional.of(token);
  }

  /**
   * Whether a {@code WWW-Authenticate} value has a Bearer challenge with
   * {@code error="invalid_token"}: compared without blanks and quotes, case-insensitively.
   */
  private static boolean marksInvalid(@Nullable String challenge) {
    if (challenge == null) {
      return false;
    }
    var compact = new StringBuilder(challenge.length());
    for (char c : challenge.toLowerCase(Locale.ROOT).toCharArray()) {
      if (!Character.isWhitespace(c) && c != '"') {
        compact.append(c);
      }
    }
    var text = compact.toString();
    for (int at = text.indexOf(SCHEME); at >= 0; at = text.indexOf(SCHEME, at + 1)) {
      if ((at == 0 || text.charAt(at - 1) == ',')
          && ("," + text.substring(at + SCHEME.length()) + ",").contains(MARKER)) {
        return true;
      }
    }
    return false;
  }
}
