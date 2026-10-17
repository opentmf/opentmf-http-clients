package org.opentmf.client.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.HttpRequest;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.model.ClientType;
import org.opentmf.client.reactive.service.api.TokenService;
import org.opentmf.client.rest.service.api.SyncTokenService;
import org.opentmf.client.starter.registry.HttpClientRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * {@code fixed-headers} belong to a client's API calls and never reach its token endpoint, on
 * every client shape; {@code bearer-auth.headers} is the opt-in for a header the token endpoint
 * needs, and reaches the token call only. Reactive clients built from the one shared
 * {@code WebClient.Builder} keep their headers and base URL to themselves.
 */
class TokenCallHeadersIT {

  private static final String TOKEN_JSON =
      "{\"access_token\":\"t\",\"expires_in\":3600,\"token_type\":\"Bearer\"}";
  private static final String API_KEY = "x-api-key";

  @Configuration(proxyBeanMethods = false)
  static class ReactiveInfraConfig {

    @Bean
    WebClient.Builder webClientBuilder() {
      return WebClient.builder();
    }
  }

  private static ClientAndServer mockServer;
  private static String baseUrl;

  @BeforeAll
  static void startMockServer() {
    mockServer = ClientAndServer.startClientAndServer();
    baseUrl = "http://localhost:" + mockServer.getLocalPort();
  }

  @AfterAll
  static void stopMockServer() {
    mockServer.stop();
  }

  @BeforeEach
  void reset() {
    mockServer.reset();
    mockServer.when(request().withMethod("POST"))
        .respond(response().withHeader("Content-Type", "application/json").withBody(TOKEN_JSON));
    mockServer.when(request().withMethod("GET")).respond(response().withBody("ok"));
  }

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(OpentmfHttpClientsAutoConfiguration.class))
      .withUserConfiguration(ReactiveInfraConfig.class);

  private static String[] bearerClient(String id, String... extra) {
    var prefix = "opentmf.http-clients." + id + ".";
    var base = new String[] {
        prefix + "bearer-auth.token-url=" + baseUrl + "/token-" + id,
        prefix + "bearer-auth.client-id=client",
        prefix + "bearer-auth.client-secret=secret",
        prefix + "bearer-auth.form-data.grant_type=client_credentials"};
    var all = new String[base.length + extra.length];
    System.arraycopy(base, 0, all, 0, base.length);
    for (int i = 0; i < extra.length; i++) {
      all[base.length + i] = prefix + extra[i];
    }
    return all;
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache"})
  void sync_fixedHeaders_reachTheApiCall_butNotTheTokenCall(String clientType) {
    contextRunner
        .withPropertyValues("opentmf.client-type=" + clientType)
        .withPropertyValues(bearerClient("catalog", "base-url=" + baseUrl + "/catalog",
            "fixed-headers.x-api-key=KEY-A"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          ((SyncTokenService) context.getBean("catalogTokenService")).getToken();
          ((RestClient) context.getBean("catalogRestClient")).get().uri("/api").retrieve()
              .body(String.class);

          var token = single("/token-catalog");
          assertThat(token.containsHeader(API_KEY)).isFalse();
          assertThat(token.getFirstHeader("Content-Type"))
              .startsWith("application/x-www-form-urlencoded");
          assertThat(token.getFirstHeader("Authorization")).startsWith("Basic ");
          assertThat(single("/catalog/api").getFirstHeader(API_KEY)).isEqualTo("KEY-A");
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache"})
  void sync_bearerAuthHeaders_reachTheTokenCallOnly(String clientType) {
    contextRunner
        .withPropertyValues("opentmf.client-type=" + clientType)
        .withPropertyValues(bearerClient("catalog", "base-url=" + baseUrl + "/catalog",
            "bearer-auth.headers.x-idp-key=IDP-KEY"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          ((SyncTokenService) context.getBean("catalogTokenService")).getToken();
          ((RestClient) context.getBean("catalogRestClient")).get().uri("/api").retrieve()
              .body(String.class);

          assertThat(single("/token-catalog").getFirstHeader("x-idp-key")).isEqualTo("IDP-KEY");
          assertThat(single("/catalog/api").containsHeader("x-idp-key")).isFalse();
        });
  }

  @Test
  void sync_theDxlCase_sameHeaderOnBothCalls_whenConfiguredOnBoth() {
    contextRunner
        .withPropertyValues("opentmf.client-type=jdk")
        .withPropertyValues(bearerClient("backend", "base-url=" + baseUrl + "/backend",
            "fixed-headers.x-api-key=KEY-A", "bearer-auth.headers.x-api-key=KEY-A"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          ((SyncTokenService) context.getBean("backendTokenService")).getToken();
          ((RestClient) context.getBean("backendRestClient")).get().uri("/api").retrieve()
              .body(String.class);

          assertThat(single("/token-backend").getHeader(API_KEY)).containsExactly("KEY-A");
          assertThat(single("/backend/api").getHeader(API_KEY)).containsExactly("KEY-A");
        });
  }

  @Test
  void sync_bearerAuthHeaders_cannotReplaceTheTokenProtocolHeaders() {
    contextRunner
        .withPropertyValues("opentmf.client-type=jdk")
        .withPropertyValues(bearerClient("catalog",
            "bearer-auth.headers.Content-Type=application/json",
            "bearer-auth.headers.Authorization=Bearer nope"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          ((SyncTokenService) context.getBean("catalogTokenService")).getToken();

          var token = single("/token-catalog");
          assertThat(token.getFirstHeader("Content-Type"))
              .startsWith("application/x-www-form-urlencoded");
          assertThat(token.getHeader("Authorization")).singleElement()
              .asString().startsWith("Basic ");
        });
  }

  @Test
  void reactive_fixedHeaders_reachTheApiCall_butNotTheTokenCall_andBearerAuthHeadersTheReverse() {
    contextRunner
        .withPropertyValues("opentmf.client-type=netty")
        .withPropertyValues(bearerClient("journal", "base-url=" + baseUrl + "/journal",
            "fixed-headers.x-api-key=KEY-A", "bearer-auth.headers.x-idp-key=IDP-KEY"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          ((TokenService) context.getBean("journalTokenService")).getToken().block();
          ((WebClient) context.getBean("journalWebClient")).get().uri("/api").retrieve()
              .bodyToMono(String.class).block();

          var token = single("/token-journal");
          assertThat(token.containsHeader(API_KEY)).isFalse();
          assertThat(token.getFirstHeader("x-idp-key")).isEqualTo("IDP-KEY");
          assertThat(token.getFirstHeader("Authorization")).startsWith("Basic ");
          var api = single("/journal/api");
          assertThat(api.getFirstHeader(API_KEY)).isEqualTo("KEY-A");
          assertThat(api.containsHeader("x-idp-key")).isFalse();
        });
  }

  @Test
  void reactive_twoClients_neitherHeadersNorBaseUrlBleedFromOneToTheOther() {
    contextRunner
        .withPropertyValues("opentmf.client-type=netty")
        .withPropertyValues(bearerClient("alpha", "base-url=" + baseUrl + "/alpha",
            "fixed-headers.x-api-key=KEY-A"))
        .withPropertyValues(bearerClient("beta", "fixed-headers.x-beta=B"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          ((TokenService) context.getBean("alphaTokenService")).getToken().block();
          ((TokenService) context.getBean("betaTokenService")).getToken().block();
          var alpha = (WebClient) context.getBean("alphaWebClient");
          var beta = (WebClient) context.getBean("betaWebClient");
          alpha.get().uri("/api").retrieve().bodyToMono(String.class).block();
          beta.get().uri(URI.create(baseUrl + "/beta-call")).retrieve()
              .bodyToMono(String.class).block();

          for (var tokenPath : new String[] {"/token-alpha", "/token-beta"}) {
            var token = single(tokenPath);
            assertThat(token.containsHeader(API_KEY)).as(tokenPath).isFalse();
            assertThat(token.containsHeader("x-beta")).as(tokenPath).isFalse();
          }
          var alphaApi = single("/alpha/api");
          assertThat(alphaApi.getHeader(API_KEY)).containsExactly("KEY-A");
          assertThat(alphaApi.containsHeader("x-beta")).isFalse();
          var betaApi = single("/beta-call");
          assertThat(betaApi.getHeader("x-beta")).containsExactly("B");
          assertThat(betaApi.containsHeader(API_KEY)).isFalse();
          assertThat(betaApi.getHeader("Accept")).containsExactly("application/json");

          // beta has no base-url: a relative URI must not resolve against alpha's (where it goes
          // instead depends on the machine, hence only what must NOT happen is asserted)
          var relative = beta.get().uri("/relative").retrieve().bodyToMono(String.class);
          catchThrowable(relative::block);
          assertThat(mockServer.retrieveRecordedRequests(request().withPath("/alpha/relative")))
              .isEmpty();
        });
  }

  @Test
  void dynamicClients_fixedHeadersNeverReachTheTokenCall() {
    contextRunner
        .withPropertyValues("opentmf.http-clients.unused.base-url=http://localhost:9999")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var registry = context.getBean(HttpClientRegistry.class);

          var sync = registry.getOrCreate("dynSync", ClientType.JDK, dynamicProperties("sync"));
          sync.tokenService().getToken();
          var reactive = registry.getOrCreateReactive("dynReactive",
              dynamicProperties("reactive"));
          reactive.tokenService().getToken().block();

          for (var tokenPath : new String[] {"/token-sync", "/token-reactive"}) {
            var token = single(tokenPath);
            assertThat(token.containsHeader(API_KEY)).as(tokenPath).isFalse();
            assertThat(token.getFirstHeader("x-idp-key")).as(tokenPath).isEqualTo("IDP-KEY");
          }
        });
  }

  private static ClientProperties dynamicProperties(String id) {
    var bearer = new BearerAuthConfig();
    bearer.setTokenUrl(URI.create(baseUrl + "/token-" + id));
    bearer.setClientId("client");
    bearer.setClientSecret("secret");
    bearer.setFormData(Map.of("grant_type", "client_credentials"));
    bearer.setHeaders(Map.of("x-idp-key", "IDP-KEY"));
    var properties = new ClientProperties();
    properties.setBaseUrl(baseUrl);
    properties.setFixedHeaders(Map.of(API_KEY, "KEY-A"));
    properties.setBearerAuth(bearer);
    return properties;
  }

  private static HttpRequest single(String path) {
    var recorded = mockServer.retrieveRecordedRequests(request().withPath(path));
    assertThat(recorded).as("requests to %s", path).hasSize(1);
    return recorded[0];
  }
}
