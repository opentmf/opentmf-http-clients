package org.opentmf.client.common.util;

import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.client.common.model.ClientProperties;
import org.springframework.beans.BeanUtils;

/**
 * The client settings a bearer token retrieval runs on: the client's own, except where
 * {@code bearer-auth} sets the token request's own {@code request-timeout},
 * {@code response-timeout} or {@code num-retries}.
 */
public final class TokenCallProperties {

  private TokenCallProperties() {
  }

  /**
   * The settings for the given client's token calls. When the client has no bearer auth, or its
   * bearer auth sets none of the token request's own bounds, this is the client's own instance,
   * so nothing differs from the client. Otherwise it is a shallow copy of the client's settings
   * carrying the token request's own bounds; the client's instance is never changed.
   */
  public static ClientProperties of(ClientProperties client) {
    BearerAuthConfig bearer = client.getBearerAuth();
    if (bearer == null || !hasOwnBounds(bearer)) {
      return client;
    }
    var token = new ClientProperties();
    BeanUtils.copyProperties(client, token);
    if (bearer.getRequestTimeout() != null) {
      token.setRequestTimeout(bearer.getRequestTimeout());
    }
    if (bearer.getResponseTimeout() != null) {
      token.setResponseTimeout(bearer.getResponseTimeout());
    }
    if (bearer.getNumRetries() != null) {
      token.setNumRetries(bearer.getNumRetries());
    }
    return token;
  }

  private static boolean hasOwnBounds(BearerAuthConfig bearer) {
    return bearer.getRequestTimeout() != null || bearer.getResponseTimeout() != null
        || bearer.getNumRetries() != null;
  }
}
