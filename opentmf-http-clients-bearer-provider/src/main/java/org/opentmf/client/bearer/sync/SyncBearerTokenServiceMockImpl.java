package org.opentmf.client.bearer.sync;

import static org.opentmf.client.common.util.TokenUtil.TOKEN_TYPE_BEARER;

public class SyncBearerTokenServiceMockImpl implements SyncBearerTokenService {

  private static final String TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";

  @Override
  public String getTokenType() {
    return TOKEN_TYPE_BEARER;
  }

  @Override
  public String getToken() {
    return TOKEN;
  }

  @Override
  public String getToken(String additionalScopes) {
    return TOKEN;
  }

  @Override
  public void clearCache() {
    // the mock keeps no state — nothing to clear
  }
}
