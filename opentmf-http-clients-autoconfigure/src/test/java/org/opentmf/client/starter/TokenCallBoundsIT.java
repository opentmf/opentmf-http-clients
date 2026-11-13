package org.opentmf.client.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockserver.integration.ClientAndServer;
import org.opentmf.client.reactive.service.api.TokenService;
import org.opentmf.client.rest.service.api.SyncTokenService;
import org.opentmf.client.starter.rest.RestClientRegistrar;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * A token retrieval runs on its own bounds when {@code bearer-auth.response-timeout} /
 * {@code bearer-auth.num-retries} are set, and on the client's own (today's behaviour) when they
 * are not. The client's own business calls keep the client's bounds either way.
 *
 * <p>The client below is tuned for a slow backend: a response timeout of {@value #CLIENT_TIMEOUT_MS}
 * ms and three status retries. The identity provider either does not answer (a delay far beyond
 * every timeout) or answers 503.</p>
 */
class TokenCallBoundsIT {

  private static final long CLIENT_TIMEOUT_MS = 1500;
  private static final long TOKEN_TIMEOUT_MS = 200;
  private static final String TOKEN_JSON =
      "{\"access_token\":\"t\",\"expires_in\":3600,\"token_type\":\"Bearer\"}";

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
    // the backend answers slower than the token bound, faster than the client's own
    mockServer.when(request().withMethod("GET").withPath("/backend/api"))
        .respond(response().withBody("ok").withDelay(TimeUnit.MILLISECONDS, 600));
  }

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(OpentmfHttpClientsAutoConfiguration.class))
      .withUserConfiguration(ReactiveInfraConfig.class);

  private static String[] slowBackendClient(String clientType, String... extra) {
    var prefix = "opentmf.http-clients.backend.";
    var base = new String[] {
        "opentmf.client-type=" + clientType,
        prefix + "base-url=" + baseUrl + "/backend",
        prefix + "response-timeout=" + CLIENT_TIMEOUT_MS + "ms",
        prefix + "num-retries=3",
        prefix + "retry-wait-duration=10ms",
        prefix + "bearer-auth.token-url=" + baseUrl + "/token",
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

  private static void silentIdentityProvider() {
    mockServer.when(request().withMethod("POST").withPath("/token"))
        .respond(response().withBody(TOKEN_JSON).withDelay(TimeUnit.SECONDS, 30));
  }

  private static void unavailableIdentityProvider() {
    mockServer.when(request().withMethod("POST").withPath("/token"))
        .respond(response().withStatusCode(503));
  }

  private static void mint(AssertableApplicationContext context) {
    var tokenService = context.getBean("backendTokenService");
    if (tokenService instanceof TokenService reactive) {
      reactive.getToken().block();
    } else {
      ((SyncTokenService) tokenService).getToken();
    }
  }

  /** Milliseconds a failing mint takes. */
  private static long failedMintMillis(AssertableApplicationContext context) {
    long start = System.nanoTime();
    var failure = catchThrowable(() -> mint(context));
    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    assertThat(failure).as("the mint must fail").isNotNull();
    return elapsed;
  }

  private static int tokenCalls() {
    return mockServer.retrieveRecordedRequests(request().withPath("/token")).length;
  }

  private static String businessCall(AssertableApplicationContext context) {
    if (context.containsBean("backendWebClient")) {
      return ((WebClient) context.getBean("backendWebClient")).get().uri("/api").retrieve()
          .bodyToMono(String.class).block(Duration.ofSeconds(10));
    }
    return ((RestClient) context.getBean("backendRestClient")).get().uri("/api").retrieve()
        .body(String.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache", "netty"})
  void nothingSet_aSilentIdentityProvider_holdsTheMintForTheClientsOwnTimeout(String type) {
    silentIdentityProvider();
    contextRunner.withPropertyValues(slowBackendClient(type)).run(context -> {
      assertThat(context).hasNotFailed();
      // two transport tries, each bounded by the client's own response timeout
      assertThat(failedMintMillis(context)).isGreaterThanOrEqualTo(2 * CLIENT_TIMEOUT_MS);
      assertThat(tokenCalls()).isEqualTo(2);
    });
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache", "netty"})
  void tokenResponseTimeoutSet_aSilentIdentityProvider_endsTheMintWithinIt(String type) {
    silentIdentityProvider();
    contextRunner
        .withPropertyValues(slowBackendClient(type,
            "bearer-auth.response-timeout=" + TOKEN_TIMEOUT_MS + "ms"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          // two transport tries of TOKEN_TIMEOUT_MS each, far below one client timeout
          assertThat(failedMintMillis(context)).isLessThan(CLIENT_TIMEOUT_MS);
          assertThat(tokenCalls()).isEqualTo(2);
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache", "netty"})
  void tokenResponseTimeoutSet_theBusinessCallKeepsTheClientsOwnTimeout(String type) {
    contextRunner
        .withPropertyValues(slowBackendClient(type,
            "bearer-auth.response-timeout=" + TOKEN_TIMEOUT_MS + "ms"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          // the backend answers after 600 ms: beyond the token bound, within the client's own
          assertThat(businessCall(context)).isEqualTo("ok");
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache", "netty"})
  void nothingSet_anUnavailableIdentityProvider_getsTheClientsOwnStatusRetries(String type) {
    unavailableIdentityProvider();
    contextRunner.withPropertyValues(slowBackendClient(type)).run(context -> {
      assertThat(context).hasNotFailed();
      failedMintMillis(context);
      assertThat(tokenCalls()).isEqualTo(1 + 3);
    });
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache", "netty"})
  void tokenNumRetriesSet_anUnavailableIdentityProvider_getsOnlyThoseRetries(String type) {
    unavailableIdentityProvider();
    contextRunner
        .withPropertyValues(slowBackendClient(type, "bearer-auth.num-retries=0"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          failedMintMillis(context);
          assertThat(tokenCalls()).isEqualTo(1);
          // the client's own num-retries, the value its business calls opt into, is untouched
          var config = context.getBean(OpentmfHttpClientsConfig.class);
          assertThat(config.getHttpClients().get("backend").getNumRetries()).isEqualTo(3);
        });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void apache_tokenRequestTimeoutSet_aMintWaitingForAPooledConnection_endsWithinIt(
      boolean tokenRequestTimeoutSet) {
    mockServer.when(request().withMethod("POST").withPath("/token"))
        .respond(response().withHeader("Content-Type", "application/json").withBody(TOKEN_JSON));
    var extra = tokenRequestTimeoutSet
        ? new String[] {"max-connections=1", "bearer-auth.request-timeout=100ms"}
        : new String[] {"max-connections=1"};
    contextRunner.withPropertyValues(slowBackendClient("apache", extra)).run(context -> {
      assertThat(context).hasNotFailed();
      // the one pooled connection is held by a business call for 600 ms
      var business = CompletableFuture.supplyAsync(() -> businessCall(context));
      await().atMost(Duration.ofSeconds(5)).until(() -> mockServer
          .retrieveRecordedRequests(request().withPath("/backend/api")).length == 1);
      if (tokenRequestTimeoutSet) {
        // two transport tries, each waiting 100 ms for the connection, not the client's 30 s
        assertThat(failedMintMillis(context)).isLessThan(450);
      } else {
        // today: the mint waits for the connection under the client's own request-timeout
        mint(context);
      }
      assertThat(business.get(10, TimeUnit.SECONDS)).isEqualTo("ok");
    });
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache"})
  void tokenNumRetriesOnly_theRegistrarWithoutTheRestTemplate_stillBuildsTheTokenService(
      String type) {
    unavailableIdentityProvider();
    contextRunner.withPropertyValues(slowBackendClient(type, "bearer-auth.num-retries=0"))
        .run(context -> {
          var registrar = context.getBean(RestClientRegistrar.class);
          var properties = context.getBean(OpentmfHttpClientsConfig.class).getHttpClients()
              .get("backend");
          var restClient = (RestClient) context.getBean("backendRestClient");

          var tokenService = registrar.createTokenService(restClient, properties);

          assertThat(catchThrowable(tokenService::getToken)).isNotNull();
          assertThat(tokenCalls()).isEqualTo(1);
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache"})
  void tokenTimeoutSet_theRegistrarWithoutTheRestTemplate_failsByName(String type) {
    contextRunner
        .withPropertyValues(slowBackendClient(type, "bearer-auth.response-timeout=200ms"))
        .run(context -> {
          var registrar = context.getBean(RestClientRegistrar.class);
          var properties = context.getBean(OpentmfHttpClientsConfig.class).getHttpClients()
              .get("backend");
          var restClient = (RestClient) context.getBean("backendRestClient");

          assertThat(catchThrowable(() -> registrar.createTokenService("backend", restClient,
              properties)))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("'backend'")
              .hasMessageContaining("createTokenService(clientId, restTemplate, restClient");
        });
  }
}
