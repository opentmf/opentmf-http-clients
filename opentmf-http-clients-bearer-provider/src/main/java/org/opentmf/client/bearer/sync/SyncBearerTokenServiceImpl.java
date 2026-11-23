package org.opentmf.client.bearer.sync;

import static org.opentmf.client.bearer.util.BearerTokenUtil.SCOPE;
import static org.opentmf.client.bearer.util.BearerTokenUtil.enrichFormData;
import static org.opentmf.client.bearer.util.BearerTokenUtil.findScope;
import static org.opentmf.client.bearer.util.BearerTokenUtil.findUsername;
import static org.opentmf.client.common.util.TokenUtil.TOKEN_TYPE_BEARER;
import static org.opentmf.client.common.util.TokenUtil.cacheKey;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.opentmf.client.bearer.model.TokenEntry;
import org.opentmf.client.bearer.util.BearerTokenUtil;
import org.opentmf.client.bearer.util.TokenCacheUtil;
import org.opentmf.client.bearer.util.TokenMints;
import org.opentmf.client.common.model.BearerAuthConfig;
import tools.jackson.databind.node.ObjectNode;

/**
 * Bearer tokens for a sync client, cached per token URL, scope and username. Callers of one key
 * that find no cached token while its mint is in flight wait for that mint and share its
 * outcome; with {@code bearer-auth.failure-memory} set, a retryable failure is remembered for a
 * while, and with {@code bearer-auth.refusal-memory} set, a refused credential (see
 * {@link TokenMints}).
 */
@Slf4j
public class SyncBearerTokenServiceImpl implements SyncBearerTokenService {

  private final BearerAuthConfig config;
  private final Cache<String, TokenEntry> tokenCache;
  private final SyncTokenClientImpl syncTokenClient;
  private final TokenMints mints;

  /**
   * A {@code Retry-After} lengthens the failure memory up to the default
   * {@code max-retry-after}.
   */
  public SyncBearerTokenServiceImpl(BearerAuthConfig config, Cache<String, TokenEntry> tokenCache,
      SyncTokenClientImpl syncTokenClient) {
    this(config, tokenCache, syncTokenClient, TokenMints.DEFAULT_MAX_RETRY_AFTER);
  }

  /**
   * @param maxRetryAfter the client's {@code max-retry-after}: the longest {@code Retry-After}
   *                      that lengthens the failure memory
   */
  public SyncBearerTokenServiceImpl(BearerAuthConfig config, Cache<String, TokenEntry> tokenCache,
      SyncTokenClientImpl syncTokenClient, Duration maxRetryAfter) {
    this.config = config;
    this.tokenCache = tokenCache;
    this.syncTokenClient = syncTokenClient;
    this.mints = new TokenMints(tokenCache, config.getFailureMemory(),
        config.getRefusalMemory(), maxRetryAfter, Ticker.systemTicker());
  }

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
    mints.forget();
  }

  @Override
  public void dropRefusedToken(String token) {
    TokenCacheUtil.dropToken(tokenCache, token, config.getTokenField());
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

    var entry = mints.get(key, () -> {
      var multiValueMap = enrichFormData(config, username, scope, enricher);
      ObjectNode tokenData = syncTokenClient.getToken(config.getTokenUrl(), multiValueMap);
      return TokenEntry.from(tokenData, config.getExpiresInField(),
          config.getFallbackExpiresInSeconds(), config.getCacheSafetyFactor());
    });
    return extractToken(entry.getTokenData());
  }

  private String extractToken(ObjectNode objectNode) {
    return BearerTokenUtil.extractToken(objectNode, config.getTokenField());
  }
}
