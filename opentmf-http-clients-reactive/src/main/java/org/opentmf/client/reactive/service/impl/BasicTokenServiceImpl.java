package org.opentmf.client.reactive.service.impl;

import static org.opentmf.client.common.util.TokenUtil.TOKEN_TYPE_BASIC;
import static java.nio.charset.StandardCharsets.US_ASCII;

import java.nio.charset.Charset;
import org.opentmf.client.common.model.BasicAuthConfig;
import org.opentmf.client.reactive.service.api.TokenService;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

public class BasicTokenServiceImpl implements TokenService {

  private final String token;

  public BasicTokenServiceImpl(BasicAuthConfig config) {
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
  public Mono<String> getToken() {
    return Mono.just(token);
  }

  @Override
  public Mono<String> getToken(String additionalScopes) {
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
