package org.opentmf.client.bearer.sync;

import org.opentmf.client.rest.service.api.SyncTokenService;

public interface SyncBearerTokenService extends SyncTokenService {

  void clearCache();

  /**
   * Drops the cached token equal to {@code token}, if this service holds it: an API refused it as
   * invalid (a {@code 401} with {@code error="invalid_token"}). The next token request then mints
   * a fresh token; a token minted meanwhile is a different value and stays. Does nothing for a
   * token this service does not hold. The library's clients call it on such a refusal of their
   * own calls; the refused call itself fails as before and is not retried.
   *
   * @since 2.6.0
   */
  default void dropRefusedToken(String token) {
    // a service without a cache has nothing to drop
  }
}
