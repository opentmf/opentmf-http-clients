package org.opentmf.client.bearer.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.opentmf.client.bearer.model.TokenEntry;
import org.opentmf.client.bearer.reactive.BearerTokenClient;
import org.opentmf.client.bearer.reactive.BearerTokenServiceImpl;
import org.opentmf.client.bearer.reactive.BearerTokenServiceMockImpl;
import org.opentmf.client.bearer.sync.SyncBearerTokenServiceImpl;
import org.opentmf.client.bearer.sync.SyncBearerTokenServiceMockImpl;
import org.opentmf.client.bearer.sync.SyncTokenClientImpl;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** A token an API refused as invalid leaves the cache; every other entry stays. */
class TokenCacheUtilDropTokenTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void dropToken_removesTheEntryHoldingThatToken_only() {
    var cache = TokenCacheUtil.buildTokenCache();
    cache.put("orders", entry("refused"));
    cache.put("journal", entry("fresh"));

    assertThat(TokenCacheUtil.dropToken(cache, "refused", "access_token")).isTrue();

    assertThat(cache.asMap()).containsOnlyKeys("journal");
  }

  @Test
  void dropToken_ofATokenNotHeld_changesNothing() {
    var cache = TokenCacheUtil.buildTokenCache();
    cache.put("orders", entry("fresh"));

    assertThat(TokenCacheUtil.dropToken(cache, "stranger", "access_token")).isFalse();

    assertThat(cache.asMap()).containsOnlyKeys("orders");
  }

  @Test
  void theSyncService_dropsTheRefusedToken() {
    var cache = TokenCacheUtil.buildTokenCache();
    cache.put("orders", entry("refused"));
    var config = config();
    var service = new SyncBearerTokenServiceImpl(config, cache,
        new SyncTokenClientImpl(RestClient.create(), config));

    service.dropRefusedToken("refused");

    assertThat(cache.asMap()).isEmpty();
  }

  @Test
  void theReactiveService_dropsTheRefusedToken() {
    var cache = TokenCacheUtil.buildTokenCache();
    cache.put("orders", entry("refused"));
    BearerTokenClient neverCalled = (uri, form) -> Mono.error(new AssertionError("no mint"));
    var service = new BearerTokenServiceImpl(config(), cache, neverCalled);

    service.dropRefusedToken("refused");

    assertThat(cache.asMap()).isEmpty();
  }

  @Test
  void theMockServices_haveNothingToDrop() {
    new SyncBearerTokenServiceMockImpl().dropRefusedToken("any");
    new BearerTokenServiceMockImpl().dropRefusedToken("any");
    assertThat(new SyncBearerTokenServiceMockImpl().getToken()).isNotBlank();
  }

  private static BearerAuthConfig config() {
    var config = new BearerAuthConfig();
    config.setTokenUrl(URI.create("http://idp/token"));
    config.setFormData(Map.of("grant_type", "client_credentials"));
    return config;
  }

  private static TokenEntry entry(String token) {
    var data = JSON.createObjectNode().put("access_token", token).put("expires_in", 3600);
    return TokenEntry.from(data, "expires_in", 300, 0.9);
  }
}
