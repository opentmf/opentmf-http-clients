package org.opentmf.client.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpError.error;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;
import org.mockserver.model.HttpRequest;
import org.opentmf.client.bearer.sync.SyncBearerTokenService;
import org.opentmf.client.reactive.service.api.TokenService;
import org.opentmf.client.rest.service.api.SyncTokenService;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/**
 * The {@code opentmf.client.token.fetch{client,outcome}} counter: registered per bearer client
 * when a {@link MeterRegistry} bean exists (static sync and reactive clients alike), absent —
 * and harmless — without one.
 */
class TokenFetchMetersIT {

  @Configuration(proxyBeanMethods = false)
  static class ReactiveInfraConfig {

    @Bean
    WebClient.Builder webClientBuilder() {
      return WebClient.builder();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class MeterRegistryConfig {

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  private static final String TOKEN_JSON =
      "{\"access_token\":\"test-bearer-token\",\"expires_in\":3600,\"token_type\":\"Bearer\"}";

  private static ClientAndServer mockServer;
  private static String tokenUrl;

  @BeforeAll
  static void startMockServer() {
    mockServer = ClientAndServer.startClientAndServer();
    tokenUrl = "http://localhost:" + mockServer.getLocalPort() + "/token";
  }

  @AfterAll
  static void stopMockServer() {
    mockServer.stop();
  }

  @BeforeEach
  void resetExpectations() {
    mockServer.reset();
  }

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(OpentmfHttpClientsAutoConfiguration.class))
      .withUserConfiguration(ReactiveInfraConfig.class);

  @Test
  void syncBearerClient_countsMintsByOutcome() {
    // first mint: dropped once then answered -> retried; second mint after clearCache: ok
    mockServer.when(tokenRequest(), Times.once()).error(error().withDropConnection(true));
    mockServer.when(tokenRequest()).respond(response()
        .withHeader("Content-Type", "application/json").withBody(TOKEN_JSON));

    contextRunner
        .withUserConfiguration(MeterRegistryConfig.class)
        .withPropertyValues(
            "opentmf.client-type=jdk",
            "opentmf.http-clients.catalog.base-url=http://localhost:9999",
            "opentmf.http-clients.catalog.bearer-auth.token-url=" + tokenUrl,
            "opentmf.http-clients.catalog.bearer-auth.client-id=client",
            "opentmf.http-clients.catalog.bearer-auth.client-secret=secret",
            "opentmf.http-clients.catalog.bearer-auth.form-data.grant_type=client_credentials")
        .run(context -> {
          assertThat(context).hasNotFailed().hasSingleBean(TokenFetchMeters.class);
          var tokenService = (SyncTokenService) context.getBean("catalogTokenService");
          var meterRegistry = context.getBean(MeterRegistry.class);

          assertThat(tokenService.getToken()).isEqualTo("test-bearer-token");
          assertThat(count(meterRegistry, "catalog", "retried")).isEqualTo(1.0);
          assertThat(count(meterRegistry, "catalog", "ok")).isZero();

          tokenService.getToken(); // cache hit: not a mint, not counted
          assertThat(count(meterRegistry, "catalog", "ok")).isZero();

          ((SyncBearerTokenService) tokenService).clearCache();
          tokenService.getToken();
          assertThat(count(meterRegistry, "catalog", "ok")).isEqualTo(1.0);
          assertThat(count(meterRegistry, "catalog", "failed")).isZero();
        });
  }

  @Test
  void reactiveBearerClient_countsMintsByOutcome() {
    mockServer.when(tokenRequest()).respond(response().withStatusCode(401)
        .withHeader("Content-Type", "application/json").withBody("{\"error\":\"invalid_client\"}"));

    contextRunner
        .withUserConfiguration(MeterRegistryConfig.class)
        .withPropertyValues(
            "opentmf.client-type=netty",
            "opentmf.http-clients.journal.base-url=http://localhost:9999",
            "opentmf.http-clients.journal.num-retries=0",
            "opentmf.http-clients.journal.bearer-auth.token-url=" + tokenUrl,
            "opentmf.http-clients.journal.bearer-auth.client-id=client",
            "opentmf.http-clients.journal.bearer-auth.client-secret=secret",
            "opentmf.http-clients.journal.bearer-auth.form-data.grant_type=client_credentials")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var tokenService = (TokenService) context.getBean("journalTokenService");
          var meterRegistry = context.getBean(MeterRegistry.class);

          StepVerifier.create(tokenService.getToken()).expectError().verify();
          assertThat(count(meterRegistry, "journal", "failed")).isEqualTo(1.0);
          assertThat(count(meterRegistry, "journal", "ok")).isZero();
        });
  }

  @Test
  void withoutMeterRegistry_noMetersBean_andMintsStillWork() {
    mockServer.when(tokenRequest()).respond(response()
        .withHeader("Content-Type", "application/json").withBody(TOKEN_JSON));

    contextRunner
        .withPropertyValues(
            "opentmf.client-type=jdk",
            "opentmf.http-clients.catalog.base-url=http://localhost:9999",
            "opentmf.http-clients.catalog.bearer-auth.token-url=" + tokenUrl,
            "opentmf.http-clients.catalog.bearer-auth.client-id=client",
            "opentmf.http-clients.catalog.bearer-auth.client-secret=secret",
            "opentmf.http-clients.catalog.bearer-auth.form-data.grant_type=client_credentials")
        .run(context -> {
          assertThat(context).hasNotFailed().doesNotHaveBean(TokenFetchMeters.class);
          var tokenService = (SyncTokenService) context.getBean("catalogTokenService");
          assertThat(tokenService.getToken()).isEqualTo("test-bearer-token");
        });
  }

  private static double count(MeterRegistry registry, String client, String outcome) {
    var counter = registry.find(TokenFetchMeters.METER_NAME)
        .tag("client", client).tag("outcome", outcome).counter();
    return counter == null ? 0.0 : counter.count();
  }

  private static HttpRequest tokenRequest() {
    return request().withMethod("POST").withPath("/token");
  }
}
