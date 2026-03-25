package org.opentmf.client.reactive.service.impl;

import org.opentmf.client.reactive.service.api.TokenService;
import reactor.core.publisher.Mono;

public class NoOpTokenService implements TokenService {

  private static final String EMPTY = "";

  @Override
  public String getTokenType() {
    return EMPTY;
  }

  @Override
  public Mono<String> getToken() {
    return Mono.just(EMPTY);
  }

  @Override
  public Mono<String> getToken(String additionalScopes) {
    return Mono.just(EMPTY);
  }
}
