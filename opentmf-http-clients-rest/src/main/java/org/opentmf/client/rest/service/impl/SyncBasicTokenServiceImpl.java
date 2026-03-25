package org.opentmf.client.rest.service.impl;

import static org.opentmf.client.common.util.TokenUtil.TOKEN_TYPE_BASIC;
import static java.nio.charset.StandardCharsets.US_ASCII;

import java.nio.charset.Charset;
import org.opentmf.client.common.model.BasicAuthConfig;
import org.opentmf.client.rest.service.api.SyncTokenService;
import org.springframework.http.HttpHeaders;

public class SyncBasicTokenServiceImpl implements SyncTokenService {

  private final String token;

  public SyncBasicTokenServiceImpl(BasicAuthConfig config) {
    token = HttpHeaders.encodeBasicAuth(
        config.getUsername(),
        config.getPassword(),
        findCharset(config.getCharset()));
  }

  @Override
  public String getTokenType() {
    return TOKEN_TYPE_BASIC;
  }

  @Override
  public String getToken() {
    return token;
  }

  @Override
  public String getToken(String additionalScopes) {
    return getToken();
  }

  private static Charset findCharset(String charset) {
    try {
      return Charset.forName(charset);
    } catch (Exception e) {
      return US_ASCII;
    }
  }
}
