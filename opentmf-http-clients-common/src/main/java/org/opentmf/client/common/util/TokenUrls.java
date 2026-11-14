package org.opentmf.client.common.util;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * How the library judges and shows a {@code bearer-auth.token-url}. A token URL may carry a user
 * part ({@code http://user:password@host/token}); no client type sends it, and the library never
 * shows it: every log line and exception message that names the token URL uses
 * {@link #shown(URI)}.
 */
public final class TokenUrls {

  private static final Set<String> SCHEMES = Set.of("http", "https");

  private TokenUrls() {
  }

  /**
   * Whether the URL can be a token endpoint: absolute, with the scheme {@code http} or
   * {@code https}, and with a host. A user part does not matter here.
   */
  public static boolean isUsable(URI url) {
    var scheme = url.getScheme();
    var host = url.getHost();
    return url.isAbsolute() && scheme != null
        && SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))
        && host != null && !host.isBlank();
  }

  /**
   * The URL as the library shows it: the same URL without its user part. A URL without one is
   * returned as it is.
   */
  public static @Nullable URI shown(@Nullable URI url) {
    if (url == null || url.getRawUserInfo() == null || url.getRawAuthority() == null) {
      return url;
    }
    var authority = url.getRawAuthority();
    var shown = new StringBuilder()
        .append(url.getScheme()).append("://")
        .append(authority.substring(authority.lastIndexOf('@') + 1));
    if (url.getRawPath() != null) {
      shown.append(url.getRawPath());
    }
    if (url.getRawQuery() != null) {
      shown.append('?').append(url.getRawQuery());
    }
    if (url.getRawFragment() != null) {
      shown.append('#').append(url.getRawFragment());
    }
    return URI.create(shown.toString());
  }

  /**
   * The text without the URL's user part, for a text the library did not write itself (an
   * exception message of the HTTP client that quotes the URL). A URL without one leaves the
   * text as it is.
   */
  public static String scrubbed(String text, @Nullable URI url) {
    if (url == null || url.getRawUserInfo() == null) {
      return text;
    }
    var scrubbed = text.replace(url.getRawUserInfo() + "@", "");
    var decoded = url.getUserInfo();
    return decoded == null ? scrubbed : scrubbed.replace(decoded + "@", "");
  }
}
