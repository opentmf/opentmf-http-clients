package org.opentmf.client.rest.service.impl;

import org.opentmf.client.rest.service.api.SyncTokenService;

public class NoOpSyncTokenService implements SyncTokenService {

  private static final String EMPTY = "";

  @Override
  public String getTokenType() {
    return EMPTY;
  }

  @Override
  public String getToken() {
    return EMPTY;
  }

  @Override
  public String getToken(String additionalScopes) {
    return EMPTY;
  }
}
