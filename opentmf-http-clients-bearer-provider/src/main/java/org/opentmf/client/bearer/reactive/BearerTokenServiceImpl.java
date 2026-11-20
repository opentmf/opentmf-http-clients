package org.opentmf.client.bearer.reactive;

import static java.util.Collections.emptyMap;
import static org.opentmf.client.bearer.util.BearerTokenUtil.SCOPE;
import static org.opentmf.client.bearer.util.BearerTokenUtil.enrichFormData;
import static org.opentmf.client.bearer.util.BearerTokenUtil.findScope;
import static org.opentmf.client.bearer.util.BearerTokenUtil.findUsername;
import static org.opentmf.client.common.util.TokenUtil.TOKEN_TYPE_BEARER;
import static org.opentmf.client.common.util.TokenUtil.cacheKey;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Ticker;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.opentmf.client.bearer.model.TokenEntry;
import org.opentmf.client.bearer.util.BearerTokenUtil;
import org.opentmf.client.bearer.util.TokenMints;
import org.opentmf.client.common.model.BearerAuthConfig;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;

/**
 * Bearer tokens for a reactive client, cached per token URL, scope and username. Callers of one
 * key that find no cached token while its mint is in flight wait for that mint and share its
 * outcome; with {@code bearer-auth.failure-memory} set, a retryable failure is remembered for a
 * while, and with {@code bearer-auth.refusal-memory} set, a refused credential (see
 * {@link TokenMints}).
 */
@Slf4j
public class BearerTokenServiceImpl implements BearerTokenService {

  private final BearerAuthConfig config;
  private final Cache<String, TokenEntry> tokenCache;
  private final BearerTokenClient tokenClient;
  private final TokenMints mints;

  /**
   * A {@code Retry-After} lengthens the failure memory up to the default
   * {@code max-retry-after}.
   */
  public BearerTokenServiceImpl(BearerAuthConfig config, Cache<String, TokenEntry> tokenCache,
      BearerTokenClient tokenClient) {
    this(config, tokenCache, tokenClient, TokenMints.DEFAULT_MAX_RETRY_AFTER);
  }

  /**
   * @param maxRetryAfter the client's {@code max-retry-after}: the longest {@code Retry-After}
   *                      that lengthens the failure memory
   */
  public BearerTokenServiceImpl(BearerAuthConfig config, Cache<String, TokenEntry> tokenCache,
      BearerTokenClient tokenClient, Duration maxRetryAfter) {
    this.config = config;
    this.tokenCache = tokenCache;
    this.tokenClient = tokenClient;
    this.mints = new TokenMints(tokenCache, config.getFailureMemory(),
        config.getRefusalMemory(), maxRetryAfter, Ticker.systemTicker());
  }

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
    mints.forget();
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

    var mint = Mono.defer(() -> tokenClient
        .retrieveToken(uri, enrichFormData(config, username, scope, enricher))
        .map(tokenData -> TokenEntry.from(tokenData, config.getExpiresInField(),
            config.getFallbackExpiresInSeconds(), config.getCacheSafetyFactor())));
    // the mint is subscribed with the context of the caller that starts it
    return Mono.deferContextual(context -> Mono.fromFuture(
            mints.getAsync(key, () -> mint.contextWrite(context).toFuture()), true))
        .map(entry -> extractToken(entry.getTokenData()));
  }

  private String extractToken(ObjectNode objectNode) {
    return BearerTokenUtil.extractToken(objectNode, config.getTokenField());
  }
}
