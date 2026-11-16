package org.opentmf.client.common.util;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * How the library judges and shows a {@code bearer-auth.token-url}. A token URL may carry a user
 * part ({@code http://user:password@host/token}), a query ({@code ?p=policy}) and a fragment;
 * any of them can hold a credential. No client type sends the user part, the query is sent as
 * configured and the fragment never is; the library shows none of them: every log line and
 * exception message that names the token URL uses {@link #shown(URI)}, and a text of the HTTP
 * client that quotes the URL goes through {@link #scrubbed(String, URI)}.
 */
public final class TokenUrls {

  /** What {@link #shown(URI)} shows in place of an opaque URL's scheme-specific part. */
  public static final String WITHHELD = "withheld";

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
   * The URL as the library shows it: its scheme, host, port and raw path, without a user part,
   * a query or a fragment. A URL without any of them is returned as it is (the same instance);
   * an opaque URL ({@code mailto:x@y}) is shown as its scheme and {@value #WITHHELD}.
   */
  public static @Nullable URI shown(@Nullable URI url) {
    if (url == null || isShownAsItIs(url)) {
      return url;
    }
    if (url.isOpaque()) {
      return URI.create(url.getScheme() + ":" + WITHHELD);
    }
    var shown = new StringBuilder();
    var scheme = url.getScheme();
    if (scheme != null) {
      shown.append(scheme).append(':');
    }
    var authority = url.getRawAuthority();
    if (authority != null) {
      shown.append("//").append(authority.substring(authority.lastIndexOf('@') + 1));
    }
    var path = url.getRawPath();
    if (path != null) {
      shown.append(path);
    }
    return URI.create(shown.toString());
  }

  /**
   * Whether {@link #shown(URI)} returns the URL as it is: a URL that is not opaque and has no
   * user part, no query and no fragment.
   */
  public static boolean isShownAsItIs(URI url) {
    return !url.isOpaque() && url.getRawUserInfo() == null && url.getRawQuery() == null
        && url.getRawFragment() == null;
  }

  /**
   * The text without the URL's user part, query and fragment, raw or decoded, for a text the
   * library did not write itself (an exception message of the HTTP client that quotes the URL).
   * A query is removed with its {@code ?} and a fragment with its {@code #}, wherever they appear
   * (never on their own, so a short value cannot cut words out of the text). A URL without any of
   * them leaves the text as it is.
   */
  public static String scrubbed(String text, @Nullable URI url) {
    if (url == null) {
      return text;
    }
    var scrubbed = removed(text, url.getRawUserInfo(), url.getUserInfo(), "", "@");
    scrubbed = removed(scrubbed, url.getRawQuery(), url.getQuery(), "?", "");
    return removed(scrubbed, url.getRawFragment(), url.getFragment(), "#", "");
  }

  private static String removed(String text, @Nullable String raw, @Nullable String decoded,
      String before, String after) {
    var result = text;
    if (raw != null && !raw.isEmpty()) {
      result = result.replace(before + raw + after, "");
    }
    if (decoded != null && !decoded.isEmpty()) {
      result = result.replace(before + decoded + after, "");
    }
    return result;
  }
}
