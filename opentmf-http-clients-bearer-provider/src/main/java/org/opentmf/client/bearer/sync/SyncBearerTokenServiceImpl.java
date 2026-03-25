package org.opentmf.client.bearer.sync;

import static org.opentmf.client.bearer.util.BearerTokenUtil.SCOPE;
import static org.opentmf.client.bearer.util.BearerTokenUtil.findScope;
import static org.opentmf.client.bearer.util.BearerTokenUtil.findUsername;
import static org.opentmf.client.common.util.TokenUtil.TOKEN_TYPE_BEARER;
import static org.opentmf.client.common.util.TokenUtil.cacheKey;
import static org.springframework.util.StringUtils.hasText;

import com.github.benmanes.caffeine.cache.Cache;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opentmf.client.bearer.model.TokenEntry;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.databind.node.ObjectNode;

@Slf4j
@RequiredArgsConstructor
public class SyncBearerTokenServiceImpl implements SyncBearerTokenService {

  private final BearerAuthConfig config;
  private final Cache<String, TokenEntry> tokenCache;
  private final SyncTokenClientImpl syncTokenClient;

  @Override
  public String getTokenType() {
    return TOKEN_TYPE_BEARER;
  }

  @Override
  public String getToken() {
    return getTokenInternal(null, Map.of());
  }

  @Override
  public String getToken(String additionalScopes) {
    return getTokenInternal(additionalScopes, Map.of());
  }

  @Override
  public void clearCache() {
    tokenCache.invalidateAll();
  }

  private String getTokenInternal(String additionalScopes, Map<String, String> enricher) {
    var scope = findScope(additionalScopes, config.getFormData().get(SCOPE), enricher);
    var username = findUsername(config, config.getFormData(), enricher);
    var key = cacheKey(config.getTokenUrl(), scope, username);

    var cached = tokenCache.getIfPresent(key);
    if (cached != null) {
      log.trace("Returning cached bearer token (sync) for scope: {}, username: {}", scope, username);
      return extractToken(cached.getTokenData());
    }

    var multiValueMap = enrich(username, scope, enricher);
    ObjectNode tokenData = syncTokenClient.getToken(config.getTokenUrl(), multiValueMap);
    var entry = TokenEntry.from(tokenData, config.getExpiresInField(),
        config.getFallbackExpiresInSeconds(), config.getCacheSafetyFactor());
    tokenCache.put(key, entry);
    return extractToken(tokenData);
  }

  private String extractToken(ObjectNode objectNode) {
    return objectNode.get(config.getTokenField()).stringValue();
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
