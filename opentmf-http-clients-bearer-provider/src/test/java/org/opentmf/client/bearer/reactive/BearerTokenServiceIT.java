package org.opentmf.client.bearer.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.ClearType;
import org.opentmf.client.bearer.exception.BearerTokenException;
import org.opentmf.client.bearer.model.TokenEntry;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.mockserver.callback.JwksExpectationInitializer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

class BearerTokenServiceIT {

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
  private BearerTokenServiceImpl tokenService;

  @BeforeEach
  void setUp() {
    tokenCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).build();

    var bearerConfig = new BearerAuthConfig();
    bearerConfig.setTokenUrl(tokenUrl);
    bearerConfig.setClientId("client1");
    bearerConfig.setClientSecret("client1Secret");
    bearerConfig.setFormData(Map.of("grant_type", "client_credentials"));

    var clientProperties = new ClientProperties();
    clientProperties.setNumRetries(0);

    WebClient webClient = WebClient.builder().build();
    var tokenClient = new BearerTokenClientImpl(clientProperties, bearerConfig, webClient);

    tokenService = new BearerTokenServiceImpl(bearerConfig, tokenCache, tokenClient);
  }

  @Test
  void getToken_clientCredentials_returnsValidToken() {
    StepVerifier.create(tokenService.getToken())
        .assertNext(token -> {
          assertThat(token).isNotEmpty();
          assertThat(token).startsWith("eyJ");
        })
        .verifyComplete();
  }

  @Test
  void getToken_isCached_secondCallReturnsSameToken() {
    String first = tokenService.getToken().block();
    String second = tokenService.getToken().block();
    assertThat(first).isEqualTo(second);
  }

  @Test
  void clearCache_forcesRefetch() {
    tokenService.getToken().block();
    tokenService.clearCache();

    String second = tokenService.getToken().block();
    assertThat(second).isNotEmpty();
  }

  @Test
  void getToken_withAdditionalScopes() {
    StepVerifier.create(tokenService.getToken("admin"))
        .assertNext(token -> assertThat(token).startsWith("eyJ"))
        .verifyComplete();
  }

  @Test
  void getToken_invalidCredentials_returnsError() {
    var badConfig = new BearerAuthConfig();
    badConfig.setTokenUrl(tokenUrl);
    badConfig.setClientId("client1");
    badConfig.setClientSecret("wrongSecret");
    badConfig.setFormData(Map.of("grant_type", "client_credentials"));

    var clientProperties = new ClientProperties();
    clientProperties.setNumRetries(0);

    WebClient webClient = WebClient.builder().build();
    var badTokenClient = new BearerTokenClientImpl(clientProperties, badConfig, webClient);
    var badService = new BearerTokenServiceImpl(badConfig, tokenCache, badTokenClient);

    StepVerifier.create(badService.getToken())
        .expectError(BearerTokenException.class)
        .verify();
  }

  @Test
  void getTokenType_returnsBearer() {
    assertThat(tokenService.getTokenType()).isEqualTo("Bearer");
  }

  @Test
  void getToken_withEnricher() {
    StepVerifier.create(tokenService.getToken(Map.of("custom_field", "value")))
        .assertNext(token -> assertThat(token).startsWith("eyJ"))
        .verifyComplete();
  }

  @Test
  void getToken_passwordGrant() {
    var passwordConfig = new BearerAuthConfig();
    passwordConfig.setTokenUrl(tokenUrl);
    passwordConfig.setClientId("client2");
    passwordConfig.setClientSecret("client2Secret");
    passwordConfig.setFormData(Map.of("grant_type", "password"));
    passwordConfig.setUsernameField("username");

    var clientProperties = new ClientProperties();
    clientProperties.setNumRetries(0);

    WebClient webClient = WebClient.builder().build();
    var tokenClient = new BearerTokenClientImpl(clientProperties, passwordConfig, webClient);
    Cache<String, TokenEntry> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5)).build();
    var service = new BearerTokenServiceImpl(passwordConfig, cache, tokenClient);

    StepVerifier.create(service.getToken(
            Map.of("username", "admin_usr", "password", "admin_pwd")))
        .assertNext(token -> assertThat(token).startsWith("eyJ"))
        .verifyComplete();
  }

  @Test
  void getToken_publicClient_noClientSecret() {
    var publicConfig = new BearerAuthConfig();
    publicConfig.setTokenUrl(tokenUrl);
    publicConfig.setClientId("uiClient");
    publicConfig.setFormData(Map.of(
        "grant_type", "password",
        "client_id", "uiClient"));
    publicConfig.setUsernameField("username");

    var clientProperties = new ClientProperties();
    clientProperties.setNumRetries(0);

    WebClient webClient = WebClient.builder().build();
    var tokenClient = new BearerTokenClientImpl(clientProperties, publicConfig, webClient);
    Cache<String, TokenEntry> cache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(5)).build();
    var service = new BearerTokenServiceImpl(publicConfig, cache, tokenClient);

    StepVerifier.create(service.getToken(
            Map.of("username", "admin_usr", "password", "admin_pwd")))
        .assertNext(token -> assertThat(token).startsWith("eyJ"))
        .verifyComplete();
  }

  @Test
  void getToken_sendsTheBearerAuthHeaders_withoutReplacingTheProtocolHeaders() {
    mockServer.clear(request().withPath(tokenUrl.getPath()), ClearType.LOG);
    var config = new BearerAuthConfig();
    config.setTokenUrl(tokenUrl);
    config.setClientId("client1");
    config.setClientSecret("client1Secret");
    config.setFormData(Map.of("grant_type", "client_credentials"));
    config.setHeaders(Map.of("x-api-key", "KEY-A", "Accept", "text/plain"));
    var clientProperties = new ClientProperties();
    clientProperties.setNumRetries(0);
    var tokenClient = new BearerTokenClientImpl(clientProperties, config,
        WebClient.builder().build());

    StepVerifier.create(tokenClient.retrieveToken(tokenUrl,
            new LinkedMultiValueMap<>(Map.of("grant_type", List.of("client_credentials")))))
        .assertNext(token -> assertThat(token.has("access_token")).isTrue())
        .verifyComplete();

    var sent = mockServer.retrieveRecordedRequests(request().withPath(tokenUrl.getPath()));
    assertThat(sent).hasSize(1);
    assertThat(sent[0].getHeader("x-api-key")).containsExactly("KEY-A");
    assertThat(sent[0].getHeader("Accept")).containsExactly("application/json");
    assertThat(sent[0].getFirstHeader("Authorization")).startsWith("Basic ");
  }
}
