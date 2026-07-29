package org.opentmf.client.bearer.reactive;

import static org.opentmf.client.common.util.TokenUtil.TOKEN_TYPE_BEARER;

import java.net.URI;
import java.util.Map;
import reactor.core.publisher.Mono;

public class BearerTokenServiceMockImpl implements BearerTokenService {

  private static final String TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";

  @Override
  public String getTokenType() {
    return TOKEN_TYPE_BEARER;
  }

  @Override
  public Mono<String> getToken() {
    return Mono.just(TOKEN);
  }

  @Override
  public Mono<String> getToken(String additionalScopes) {
    return Mono.just(TOKEN);
  }

  @Override
  public Mono<String> getToken(URI tokenUri, String additionalScopes) {
    return Mono.just(TOKEN);
  }

  @Override
  public Mono<String> getToken(Map<String, String> enricher) {
    return Mono.just(TOKEN);
  }

  @Override
  public void clearCache() {
    // the mock keeps no state — nothing to clear
  }
}
