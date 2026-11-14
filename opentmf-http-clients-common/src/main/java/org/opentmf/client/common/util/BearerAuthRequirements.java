package org.opentmf.client.common.util;

import org.jspecify.annotations.Nullable;
import org.opentmf.client.common.model.AuthType;
import org.opentmf.client.common.model.ClientProperties;

/**
 * What a client with bearer auth needs before it is built. A {@code bearer-auth} block is bound
 * as soon as any of its keys is set, so a client that sets only, say,
 * {@code bearer-auth.num-retries} is a bearer client; without a token URL it could only fail at
 * its first token call. Nor can a token URL that is not an absolute {@code http} or
 * {@code https} URL with a host (a misspelt scheme, a space in the host, an empty host) ever be
 * called. Both are refused when the client is built instead, by name, whether or not a bean
 * validator is on the classpath.
 */
public final class BearerAuthRequirements {

  private BearerAuthRequirements() {
  }

  /**
   * Refuses a bearer-auth client without a {@code bearer-auth.token-url}, or with one that is not
   * an absolute {@code http} or {@code https} URL with a host; the refusal never quotes the URL,
   * which may carry a user part. A client with {@code bearer-auth.use-mock: true} needs no token
   * URL and is accepted, as is any client without bearer auth.
   *
   * @param clientId the client's id, named in the refusal; {@code null} when it has none
   * @throws IllegalStateException when the client has bearer auth but no usable token URL
   */
  public static void requireTokenUrl(@Nullable String clientId, ClientProperties properties) {
    var bearer = properties.getBearerAuth();
    if (properties.getAuthType() != AuthType.BEARER || bearer.isUseMock()) {
      return;
    }
    var client = clientId == null ? "A client" : "Client '" + clientId + "'";
    var tokenUrl = bearer.getTokenUrl();
    if (tokenUrl != null) {
      if (!TokenUrls.isUsable(tokenUrl)) {
        throw new IllegalStateException(client + " has a bearer-auth.token-url that is not an "
            + "absolute http or https URL with a host.");
      }
      return;
    }
    throw new IllegalStateException(client
        + " has bearer-auth but no bearer-auth.token-url. A bearer-auth block is bound as soon "
        + "as any of its keys is set (bearer-auth.num-retries alone makes a bearer client): set "
        + "bearer-auth.token-url, remove the bearer-auth keys, or set bearer-auth.use-mock: true "
        + "for a mock token.");
  }
}
