package org.opentmf.client.starter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockserver.integration.ClientAndServer;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.model.ClientType;
import org.opentmf.client.common.resilience.ResilienceRegistries;
import org.opentmf.client.rest.resilience.ResilienceClientHttpRequestInterceptor;
import org.opentmf.client.rest.service.api.OutboundInterceptorContribution;
import org.opentmf.client.rest.service.api.RestClientFactory;
import org.opentmf.client.rest.service.api.SyncTokenService;
import org.opentmf.client.rest.util.GzipClientHttpRequestInterceptor;
import org.opentmf.client.rest.util.RefusedTokenInterceptor;
import org.opentmf.client.starter.OpentmfHttpClientsAutoConfiguration;
import org.opentmf.client.starter.registry.HttpClientRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The application's {@link OutboundInterceptorContribution} beans run FIRST — in their order,
 * ahead of the library's resilience, gzip, fixed-headers and Logbook interceptors — on every
 * library-built sync client: the {@code <id>RestClient}, the bearer-token call of {@code <id>TokenService}, and a dynamic client from
 * {@link HttpClientRegistry}. Without a contribution bean the clients are built as before.
 */
class OutboundInterceptorContributionIT {

  private static final String TOKEN_JSON =
      "{\"access_token\":\"test-bearer-token\",\"expires_in\":3600,\"token_type\":\"Bearer\"}";
  private static final List<String> SEEN = new CopyOnWriteArrayList<>();

  @Configuration(proxyBeanMethods = false)
  static class ReactiveInfraConfig {

    @Bean
    WebClient.Builder webClientBuilder() {
      return WebClient.builder();
    }
  }

  /** Declared out of order on purpose: the order value, not the declaration, decides. */
  @Configuration(proxyBeanMethods = false)
  static class RecordingContributions {

    @Bean
    OutboundInterceptorContribution lateContribution() {
      return new OutboundInterceptorContribution(ContributionTestSupport.recorder("late", SEEN),
          20);
    }

    @Bean
    OutboundInterceptorContribution earlyContribution() {
      return new OutboundInterceptorContribution(ContributionTestSupport.recorder("early", SEEN),
          10);
    }
  }

  /** Spring's rule: an {@code @Order} on the {@code @Bean} method beats the record's order. */
  @Configuration(proxyBeanMethods = false)
  static class BeanMethodOrderedContributions {

    @Bean
    @Order(30)
    OutboundInterceptorContribution demotedContribution() {
      return new OutboundInterceptorContribution(ContributionTestSupport.recorder("demoted", SEEN),
          10);
    }

    @Bean
    OutboundInterceptorContribution plainContribution() {
      return new OutboundInterceptorContribution(ContributionTestSupport.recorder("plain", SEEN),
          20);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class RejectingContribution {

    @Bean
    OutboundInterceptorContribution rejectingContribution() {
      return new OutboundInterceptorContribution((request, body, execution) -> {
        throw new IllegalStateException("outbound HTTP refused");
      });
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
    SEEN.clear();
    // the expectation only matches when the fixed header arrives: the library's chain still runs
    mockServer.when(request().withMethod("GET").withPath("/api/hello")
            .withHeader("X-Custom", "myvalue"))
        .respond(response().withStatusCode(200).withBody("hello"));
    mockServer.when(request().withMethod("POST").withPath("/token"))
        .respond(response().withHeader("Content-Type", "application/json").withBody(TOKEN_JSON));
  }

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(OpentmfHttpClientsAutoConfiguration.class))
      .withUserConfiguration(ReactiveInfraConfig.class);

  private ApplicationContextRunner catalogClient(String clientType) {
    return contextRunner.withPropertyValues(
        "opentmf.client-type=" + clientType,
        "opentmf.http-clients.catalog.base-url=" + baseUrl,
        "opentmf.http-clients.catalog.fixed-headers.X-Custom=myvalue",
        "opentmf.http-clients.catalog.resilience.enabled=true",
        "opentmf.http-clients.catalog.bearer-auth.token-url=" + baseUrl + "/token",
        "opentmf.http-clients.catalog.bearer-auth.client-id=client",
        "opentmf.http-clients.catalog.bearer-auth.client-secret=secret",
        "opentmf.http-clients.catalog.bearer-auth.form-data.grant_type=client_credentials");
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache"})
  void contributionsRunFirst_onTheRestClient_andTheTokenCall(String clientType) {
    catalogClient(clientType)
        .withUserConfiguration(RecordingContributions.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          var early = interceptorOf(context, "earlyContribution");
          var late = interceptorOf(context, "lateContribution");

          var restClient = (RestClient) context.getBean("catalogRestClient");
          var restClientChain = new ArrayList<ClientHttpRequestInterceptor>();
          restClient.mutate().requestInterceptors(restClientChain::addAll);
          assertThat(restClientChain).startsWith(early, late);
          assertThat(restClientChain.get(2))
              .isInstanceOf(ResilienceClientHttpRequestInterceptor.class);
          assertThat(restClientChain).last().isInstanceOf(BodyReadFailureGuard.class);
          assertThat(restClient.get().uri("/api/hello").retrieve().body(String.class))
              .isEqualTo("hello");
          assertThat(SEEN).containsExactly(
              "early /api/hello X-Custom=null", "late /api/hello X-Custom=null");

          SEEN.clear();
          var tokenService = (SyncTokenService) context.getBean("catalogTokenService");
          assertThat(tokenService.getToken()).isEqualTo("test-bearer-token");
          assertThat(SEEN).containsExactly(
              "early /token X-Custom=null", "late /token X-Custom=null");
        });
  }

  @Test
  void rejectingContribution_stopsTheCallAheadOfResilienceAndTheWire() {
    catalogClient("jdk")
        .withUserConfiguration(RejectingContribution.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          var restClient = (RestClient) context.getBean("catalogRestClient");
          var tokenService = (SyncTokenService) context.getBean("catalogTokenService");

          var response = restClient.get().uri("/api/hello").retrieve(); // lazy: no exchange yet
          assertThatThrownBy(() -> response.body(String.class))
              .isInstanceOf(IllegalStateException.class).hasMessage("outbound HTTP refused");
          assertThatThrownBy(tokenService::getToken)
              .isInstanceOf(IllegalStateException.class).hasMessage("outbound HTTP refused");

          assertThat(mockServer.retrieveRecordedRequests(request())).isEmpty();
          var circuitBreaker = context.getBean(ResilienceRegistries.class)
              .getCircuitBreakerRegistry().circuitBreaker("catalog");
          assertThat(circuitBreaker.getMetrics().getNumberOfBufferedCalls()).isZero();
        });
  }

  @Test
  void orderOnTheBeanMethod_takesPrecedenceOverTheContributionsOrder() {
    catalogClient("jdk")
        .withUserConfiguration(BeanMethodOrderedContributions.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          var restClient = (RestClient) context.getBean("catalogRestClient");
          restClient.get().uri("/api/hello").retrieve().body(String.class);
          assertThat(SEEN).containsExactly(
              "plain /api/hello X-Custom=null", "demoted /api/hello X-Custom=null");
        });
  }

  @Test
  void dynamicClients_carryTheContributions() {
    contextRunner
        .withUserConfiguration(RecordingContributions.class)
        .withPropertyValues("opentmf.http-clients.unused.base-url=http://localhost:9999")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var properties = new ClientProperties();
          properties.setBaseUrl(baseUrl);
          properties.setFixedHeaders(Map.of("X-Custom", "myvalue"));
          var client = context.getBean(HttpClientRegistry.class)
              .getOrCreateRestClient("dynamic", ClientType.JDK, properties);

          assertThat(client.restClient().get().uri("/api/hello").retrieve().body(String.class))
              .isEqualTo("hello");
          assertThat(SEEN).containsExactly(
              "early /api/hello X-Custom=null", "late /api/hello X-Custom=null");
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache"})
  void dynamicRestClientOnlyClients_carryTheContributions(String clientType) {
    contextRunner
        .withUserConfiguration(RecordingContributions.class)
        .withPropertyValues("opentmf.http-clients.unused.base-url=http://localhost:9999")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var properties = new ClientProperties();
          properties.setBaseUrl(baseUrl);
          properties.setFixedHeaders(Map.of("X-Custom", "myvalue"));
          var client = context.getBean(HttpClientRegistry.class)
              .getOrCreateRestClient("dynamic", ClientType.valueOf(clientType.toUpperCase()),
                  properties);

          assertThat(client.restClient().get().uri("/api/hello").retrieve().body(String.class))
              .isEqualTo("hello");
          assertThat(SEEN).containsExactly(
              "early /api/hello X-Custom=null", "late /api/hello X-Custom=null");
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache"})
  void theRestClientFactory_isABean_andItsClientsCarryTheContributions(String clientType) {
    catalogClient(clientType)
        .withUserConfiguration(RecordingContributions.class)
        .run(context -> {
          assertThat(context).hasNotFailed();
          var factories = context.getBeansOfType(RestClientFactory.class);
          assertThat(factories).isNotEmpty();
          var properties = new ClientProperties();
          properties.setBaseUrl(baseUrl);
          properties.setFixedHeaders(Map.of("X-Custom", "myvalue"));
          var type = ClientType.valueOf(clientType.toUpperCase());
          var restClient = factories.values().stream()
              .filter(factory -> type == ClientType.APACHE
                  == factory.getClass().getSimpleName().startsWith("Apache"))
              .findFirst().orElseThrow()
              .createRestClient("adHoc", properties);

          assertThat(restClient.get().uri("/api/hello").retrieve().body(String.class))
              .isEqualTo("hello");
          assertThat(SEEN).containsExactly(
              "early /api/hello X-Custom=null", "late /api/hello X-Custom=null");
        });
  }

  @Test
  void withoutContributionBeans_theLibrarysChainIsUnchanged() {
    catalogClient("jdk").run(context -> {
      assertThat(context).hasNotFailed();
      var interceptors = new ArrayList<ClientHttpRequestInterceptor>();
      ((RestClient) context.getBean("catalogRestClient")).mutate()
          .requestInterceptors(interceptors::addAll);
      // resilience, gzip, fixed headers, the bearer client's refused-token hook, then Logbook
      // (behind its body-read guard), which stays innermost
      assertThat(interceptors).hasSize(5);
      assertThat(interceptors.get(0)).isInstanceOf(ResilienceClientHttpRequestInterceptor.class);
      assertThat(interceptors.get(1)).isSameAs(GzipClientHttpRequestInterceptor.instance());
      assertThat(interceptors.get(3)).isInstanceOf(RefusedTokenInterceptor.class);
      assertThat(interceptors.get(4)).isInstanceOf(BodyReadFailureGuard.class);
    });
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache"})
  void theRegistrarsPipeline_carriesTheRefusedTokenHook_onlyWhenTheClientMintsItsTokens(
      String clientType) {
    contextRunner
        .withPropertyValues("opentmf.http-clients.unused.base-url=http://localhost:9999")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var registrar = context.getBean(RestClientRegistrar.class);
          var type = ClientType.valueOf(clientType.toUpperCase());

          var minting = new ClientProperties();
          minting.setBaseUrl(baseUrl);
          minting.setBearerAuth(new BearerAuthConfig());
          var interceptors = registrar.createPipeline("minting", type, minting).interceptors();
          // just ahead of Logbook (behind its body-read guard), which stays innermost
          assertThat(interceptors).filteredOn(RefusedTokenInterceptor.class::isInstance)
              .hasSize(1);
          assertThat(interceptors).last().isInstanceOf(BodyReadFailureGuard.class);
          assertThat(interceptors.get(interceptors.size() - 2))
              .isInstanceOf(RefusedTokenInterceptor.class);

          var mocked = new ClientProperties();
          mocked.setBaseUrl(baseUrl);
          mocked.setBearerAuth(new BearerAuthConfig());
          mocked.getBearerAuth().setUseMock(true);
          assertThat(registrar.createPipeline("mocked", type, mocked).interceptors())
              .noneMatch(RefusedTokenInterceptor.class::isInstance);

          var plain = new ClientProperties();
          plain.setBaseUrl(baseUrl);
          assertThat(registrar.createPipeline("plain", type, plain).interceptors())
              .noneMatch(RefusedTokenInterceptor.class::isInstance);
        });
  }

  private static ClientHttpRequestInterceptor interceptorOf(ApplicationContext context,
      String beanName) {
    return context.getBean(beanName, OutboundInterceptorContribution.class).interceptor();
  }
}
