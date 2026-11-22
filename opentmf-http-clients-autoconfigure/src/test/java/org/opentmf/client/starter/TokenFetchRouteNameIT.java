package org.opentmf.client.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.RequestReplySenderContext;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockserver.integration.ClientAndServer;
import org.opentmf.client.common.util.TokenRoute;
import org.opentmf.client.reactive.service.api.TokenService;
import org.opentmf.client.rest.service.api.SyncTokenService;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The token fetch's client observation carries the fixed route name {@link TokenRoute#NAME} as
 * its {@code uri}, never the token address; the declared client's own calls keep theirs.
 */
class TokenFetchRouteNameIT {

  private static final String TOKEN_JSON =
      "{\"access_token\":\"test-bearer-token\",\"expires_in\":3600,\"token_type\":\"Bearer\"}";

  private static final List<String[]> URIS = new CopyOnWriteArrayList<>();

  private static ClientAndServer mockServer;
  private static String serverUrl;

  @Configuration(proxyBeanMethods = false)
  static class ObservationConfig {

    @Bean
    ObservationRegistry observationRegistry() {
      var registry = ObservationRegistry.create();
      registry.observationConfig().observationHandler(new UriRecorder());
      return registry;
    }

    @Bean
    WebClient.Builder webClientBuilder() {
      return WebClient.builder();
    }
  }

  /** Records each client observation's {@code uri} label and the address it went to. */
  static final class UriRecorder implements ObservationHandler<Observation.Context> {

    @Override
    public boolean supportsContext(Observation.Context context) {
      // both clients' contexts, the servlet and the reactive one
      return context instanceof RequestReplySenderContext;
    }

    @Override
    public void onStop(Observation.Context context) {
      KeyValue uri = context.getLowCardinalityKeyValue("uri");
      KeyValue url = context.getHighCardinalityKeyValue("http.url");
      URIS.add(new String[] {uri == null ? null : uri.getValue(),
          url == null ? null : url.getValue()});
    }
  }

  @BeforeAll
  static void startMockServer() {
    mockServer = ClientAndServer.startClientAndServer();
    serverUrl = "http://localhost:" + mockServer.getLocalPort();
  }

  @AfterAll
  static void stopMockServer() {
    mockServer.stop();
  }

  @BeforeEach
  void reset() {
    mockServer.reset();
    URIS.clear();
    mockServer.when(request().withMethod("POST").withPath("/realms/test/token"))
        .respond(response().withHeader("Content-Type", "application/json").withBody(TOKEN_JSON));
    mockServer.when(request().withMethod("GET").withPath("/orders/7"))
        .respond(response().withHeader("Content-Type", "application/json").withBody("{}"));
  }

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(OpentmfHttpClientsAutoConfiguration.class))
      .withUserConfiguration(ObservationConfig.class);

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache"})
  void syncTokenFetch_isLabelledByItsRouteName(String clientType) {
    contextRunner
        .withPropertyValues(bearerClient("catalog", clientType))
        .run(context -> {
          assertThat(context).hasNotFailed();
          var tokenService = (SyncTokenService) context.getBean("catalogTokenService");

          assertThat(tokenService.getToken()).isEqualTo("test-bearer-token");

          assertThat(URIS).singleElement().satisfies(seen -> {
            assertThat(seen[0]).isEqualTo(TokenRoute.NAME);
            assertThat(seen[1]).endsWith("/realms/test/token");
          });
        });
  }

  @Test
  void syncDeclaredClient_keepsItsOwnLabel() {
    contextRunner
        .withPropertyValues(bearerClient("catalog", "jdk"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          var restClient = (RestClient) context.getBean("catalogRestClient");

          restClient.get().uri("/orders/{id}", 7).retrieve().toBodilessEntity();

          assertThat(URIS).singleElement()
              .satisfies(seen -> assertThat(seen[0]).isEqualTo("/orders/{id}"));
        });
  }

  @Test
  void reactiveTokenFetch_isLabelledByItsRouteName() {
    contextRunner
        .withPropertyValues(bearerClient("journal", "netty"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          var tokenService = (TokenService) context.getBean("journalTokenService");

          assertThat(tokenService.getToken().block()).isEqualTo("test-bearer-token");

          assertThat(URIS).singleElement().satisfies(seen -> {
            assertThat(seen[0]).isEqualTo(TokenRoute.NAME);
            assertThat(seen[1]).endsWith("/realms/test/token");
          });
        });
  }

  @Test
  void reactiveDeclaredClient_keepsItsOwnLabel() {
    contextRunner
        .withPropertyValues(bearerClient("journal", "netty"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          var webClient = (WebClient) context.getBean("journalWebClient");

          webClient.get().uri("/orders/{id}", 7).retrieve().toBodilessEntity().block();

          assertThat(URIS).singleElement()
              .satisfies(seen -> assertThat(seen[0]).isEqualTo("/orders/{id}"));
        });
  }

  @Test
  void theRouteName_isANameAndNotAPath() {
    assertThat(TokenRoute.NAME).doesNotContain("/")
        .matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*").hasSizeLessThanOrEqualTo(64);
  }

  private static String[] bearerClient(String id, String clientType) {
    var prefix = "opentmf.http-clients." + id + ".";
    return new String[] {
        "opentmf.client-type=" + clientType,
        prefix + "base-url=" + serverUrl,
        prefix + "num-retries=0",
        prefix + "bearer-auth.token-url=" + serverUrl + "/realms/test/token",
        prefix + "bearer-auth.client-id=client",
        prefix + "bearer-auth.client-secret=secret",
        prefix + "bearer-auth.form-data.grant_type=client_credentials"};
  }
}
