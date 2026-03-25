package org.opentmf.client.bearer.reactive;

import java.net.URI;
import java.util.Map;
import org.opentmf.client.reactive.service.api.TokenService;
import reactor.core.publisher.Mono;

public interface BearerTokenService extends TokenService {

  Mono<String> getToken(URI tokenUri, String additionalScopes);

  Mono<String> getToken(Map<String, String> enricher);

  void clearCache();
}
