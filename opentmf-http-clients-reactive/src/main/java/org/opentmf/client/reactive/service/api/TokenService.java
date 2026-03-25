package org.opentmf.client.reactive.service.api;

import reactor.core.publisher.Mono;

public interface TokenService {

  String getTokenType();

  Mono<String> getToken();

  Mono<String> getToken(String additionalScopes);
}
