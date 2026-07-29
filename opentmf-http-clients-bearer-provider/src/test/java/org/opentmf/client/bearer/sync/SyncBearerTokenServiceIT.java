package org.opentmf.client.bearer.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;
import org.opentmf.client.bearer.model.TokenEntry;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.mockserver.callback.JwksExpectationInitializer;
import org.springframework.web.client.RestClient;

class SyncBearerTokenServiceIT {

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

  private Cache<String, TokenEntry> tokenCache;
  private SyncBearerTokenServiceImpl tokenService;

  @BeforeEach
  void setUp() {
    tokenCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).build();

    var bearerConfig = new BearerAuthConfig();
    bearerConfig.setTokenUrl(tokenUrl);
    bearerConfig.setClientId("client1");
    bearerConfig.setClientSecret("client1Secret");
    bearerConfig.setFormData(Map.of("grant_type", "client_credentials"));

    var restClient = RestClient.create();
    var syncTokenClient = new SyncTokenClientImpl(restClient, bearerConfig);

    tokenService = new SyncBearerTokenServiceImpl(bearerConfig, tokenCache, syncTokenClient);
  }

  @Test
  void getToken_clientCredentials_returnsValidToken() {
    String token = tokenService.getToken();
    assertThat(token).isNotEmpty().startsWith("eyJ");
  }

  @Test
  void getToken_isCached_secondCallReturnsSameToken() {
    String first = tokenService.getToken();
    String second = tokenService.getToken();
    assertThat(first).isEqualTo(second);
  }

  @Test
  void clearCache_forcesRefetch() {
    tokenService.getToken();
    tokenService.clearCache();

    String second = tokenService.getToken();
    assertThat(second).isNotEmpty();
  }

  @Test
  void getToken_withAdditionalScopes() {
    String token = tokenService.getToken("admin");
    assertThat(token).isNotEmpty().startsWith("eyJ");
  }

  @Test
  void getToken_invalidCredentials_throws() {
    var badConfig = new BearerAuthConfig();
    badConfig.setTokenUrl(tokenUrl);
    badConfig.setClientId("client1");
    badConfig.setClientSecret("wrongSecret");
    badConfig.setFormData(Map.of("grant_type", "client_credentials"));

    var restClient = RestClient.create();
    var badTokenClient = new SyncTokenClientImpl(restClient, badConfig);
    var badService = new SyncBearerTokenServiceImpl(badConfig, tokenCache, badTokenClient);

    assertThatThrownBy(badService::getToken)
        .isInstanceOf(Exception.class);
  }

  @Test
  void getTokenType_returnsBearer() {
    assertThat(tokenService.getTokenType()).isEqualTo("Bearer");
  }

  @Test
  void getToken_passwordGrant() {
    var passwordConfig = new BearerAuthConfig();
    passwordConfig.setTokenUrl(tokenUrl);
    passwordConfig.setClientId("client2");
    passwordConfig.setClientSecret("client2Secret");
    passwordConfig.setFormData(Map.of(
        "grant_type", "password",
        "username", "admin_usr",
        "password", "admin_pwd"));
    passwordConfig.setUsernameField("username");

    var restClient = RestClient.create();
    var syncTokenClient = new SyncTokenClientImpl(restClient, passwordConfig);
    Cache<String, TokenEntry> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5)).build();
    var service = new SyncBearerTokenServiceImpl(passwordConfig, cache, syncTokenClient);

    String token = service.getToken();
    assertThat(token).isNotEmpty().startsWith("eyJ");
  }

  @Test
  void getToken_publicClient_noClientSecret() {
    var publicConfig = new BearerAuthConfig();
    publicConfig.setTokenUrl(tokenUrl);
    publicConfig.setClientId("uiClient");
    publicConfig.setFormData(Map.of(
        "grant_type", "password",
        "client_id", "uiClient",
        "username", "admin_usr",
        "password", "admin_pwd"));
    publicConfig.setUsernameField("username");

    var restClient = RestClient.create();
    var syncTokenClient = new SyncTokenClientImpl(restClient, publicConfig);
    Cache<String, TokenEntry> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5)).build();
    var service = new SyncBearerTokenServiceImpl(publicConfig, cache, syncTokenClient);

    String token = service.getToken();
    assertThat(token).isNotEmpty().startsWith("eyJ");
  }
}
