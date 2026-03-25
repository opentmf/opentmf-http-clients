package org.opentmf.client.bearer.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;
import org.opentmf.client.bearer.sync.SyncBearerTokenServiceImpl;
import org.opentmf.client.bearer.sync.SyncTokenClientImpl;
import org.opentmf.client.bearer.util.TokenCacheUtil;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.mockserver.callback.JwksExpectationInitializer;
import org.springframework.web.client.RestClient;

/**
 * Integration test that verifies real token cache expiry using
 * {@code shortTokenClient} from opentmf-mockserver (expires_in = 3 seconds)
 * with the production Caffeine cache ({@link TokenCacheUtil#buildTokenCache()}).
 */
class TokenCacheExpiryIT {

  private static ClientAndServer mockServer;
  private static URI tokenUrl;

  @BeforeAll
  static void startMockServer() {
    mockServer = ClientAndServer.startClientAndServer();
    mockServer.upsert(new JwksExpectationInitializer().initializeExpectations());
    tokenUrl = URI.create("http://localhost:" + mockServer.getLocalPort()
        + "/realms/realm1/protocol/openid-connect/token");
  }

  @AfterAll
  static void stopMockServer() {
    if (mockServer != null && mockServer.isRunning()) {
      mockServer.stop();
    }
  }

  private SyncBearerTokenServiceImpl tokenService;

  @BeforeEach
  void setUp() {
    var bearerConfig = new BearerAuthConfig();
    bearerConfig.setTokenUrl(tokenUrl);
    bearerConfig.setClientId("shortTokenClient");
    bearerConfig.setClientSecret("shortTokenClientSecret");
    bearerConfig.setUsernameField("username");
    bearerConfig.setFormData(Map.of(
        "grant_type", "password",
        "username", "admin_usr",
        "password", "admin_pwd"));

    var cache = TokenCacheUtil.buildTokenCache();
    var restClient = RestClient.create();
    var syncTokenClient = new SyncTokenClientImpl(restClient, bearerConfig);

    tokenService = new SyncBearerTokenServiceImpl(bearerConfig, cache, syncTokenClient);
  }

  @Test
  void tokenIsCached_withinTtl() {
    String first = tokenService.getToken();
    assertThat(first).isNotEmpty().startsWith("eyJ");

    String second = tokenService.getToken();
    assertThat(second).isEqualTo(first);
  }

  @Test
  void tokenExpires_afterTtl() throws InterruptedException {
    String first = tokenService.getToken();
    assertThat(first).isNotEmpty().startsWith("eyJ");

    // shortTokenClient has expires_in=3, cacheSafetyFactor=0.9 -> effective TTL = 2s
    Thread.sleep(3_500);

    String second = tokenService.getToken();
    assertThat(second).isNotEmpty().startsWith("eyJ");
    assertThat(second).isNotEqualTo(first);
  }
}
