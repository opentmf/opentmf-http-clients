package org.opentmf.client.bearer.reactive;

import static org.opentmf.client.bearer.util.BearerTokenUtil.SCOPE;
import static org.opentmf.client.bearer.util.BearerTokenUtil.findScope;
import static org.opentmf.client.bearer.util.BearerTokenUtil.findUsername;
import static org.opentmf.client.common.util.TokenUtil.TOKEN_TYPE_BEARER;
import static org.opentmf.client.common.util.TokenUtil.cacheKey;
import static java.util.Collections.emptyMap;
import static org.springframework.util.StringUtils.hasText;

import tools.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opentmf.client.bearer.model.TokenEntry;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Mono;

@Slf4j
@RequiredArgsConstructor
public class BearerTokenServiceImpl implements BearerTokenService {

  private final BearerAuthConfig config;
  private final Cache<String, TokenEntry> tokenCache;
  private final BearerTokenClient tokenClient;

  @Override
  public String getTokenType() {
    return TOKEN_TYPE_BEARER;
  }

  @Override
  public Mono<String> getToken() {
    return getToken(config.getTokenUrl(), null, emptyMap());
  }

  @Override
  public Mono<String> getToken(String additionalScopes) {
    return getToken(config.getTokenUrl(), additionalScopes, emptyMap());
  }

  @Override
  public Mono<String> getToken(URI tokenUri, String additionalScopes) {
    return getToken(tokenUri, additionalScopes, emptyMap());
  }

  @Override
  public Mono<String> getToken(Map<String, String> enricher) {
    return getToken(config.getTokenUrl(), null, enricher == null ? emptyMap() : enricher);
  }

  @Override
  public void clearCache() {
    tokenCache.invalidateAll();
  }

  private Mono<String> getToken(URI uri, String additionalScopes, Map<String, String> enricher) {
    var scope = findScope(additionalScopes, config.getFormData().get(SCOPE), enricher);
    var username = findUsername(config, config.getFormData(), enricher);
    var key = cacheKey(uri, scope, username);

    var cached = tokenCache.getIfPresent(key);
    if (cached != null) {
      log.trace("Returning cached bearer token for uri: {}, scope: {}, username: {}",
          uri, scope, username);
      return Mono.just(extractToken(cached.getTokenData()));
    }

    var multiValueMap = enrich(username, scope, enricher);
    return tokenClient.retrieveToken(uri, multiValueMap)
        .doOnNext(tokenData -> {
          var entry = TokenEntry.from(tokenData, config.getExpiresInField(),
              config.getFallbackExpiresInSeconds(), config.getCacheSafetyFactor());
          tokenCache.put(key, entry);
        })
        .map(this::extractToken);
  }

  private String extractToken(ObjectNode objectNode) {
    return objectNode.get(config.getTokenField()).textValue();
  }

  private MultiValueMap<String, String> enrich(String username, String scope,
      Map<String, String> enricher) {
    var map = new HashMap<>(config.getFormData());
    map.putAll(enricher);
    if (hasText(username)) {
      map.put(config.getUsernameField(), username);
    }
    if (hasText(scope)) {
      map.put(SCOPE, scope);
    }
    var linkedMap = new LinkedMultiValueMap<String, String>();
    map.forEach(linkedMap::add);
    return linkedMap;
  }
}
