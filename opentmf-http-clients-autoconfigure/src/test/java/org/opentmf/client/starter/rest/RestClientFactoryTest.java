package org.opentmf.client.starter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.model.ClientType;
import org.opentmf.client.common.model.PeerTextInMessage;
import org.opentmf.client.common.resilience.ResilienceRegistries;
import org.opentmf.client.rest.service.api.OutboundInterceptorContribution;
import org.opentmf.client.rest.service.api.RestClientFactory;
import org.opentmf.client.starter.ApachePoolMeters;
import org.opentmf.client.starter.TokenFetchMeters;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.spring.LogbookClientHttpRequestInterceptor;

/**
 * The {@link RestClientFactory} side of the JDK and Apache factories: a {@code RestClient} built
 * straight from the client's pipeline, carrying the pipeline's interceptors in their order.
 */
class RestClientFactoryTest {

  private static ClientAndServer mockServer;
  private static final RestLogbookSupport LOGBOOK_SUPPORT =
      new RestLogbookSupport(new LogbookClientHttpRequestInterceptor(Logbook.builder().build()));
  private static final ObjectProvider<RestLogbookSupport> SUPPORT_PROVIDER = provider(
      LOGBOOK_SUPPORT);
  private static final ObjectProvider<ResilienceRegistries> RESILIENCE = provider(
      new ResilienceRegistries());
  private static final ObjectProvider<ObservationRegistry> NO_OBSERVATION = provider(null);
  private static final ObjectProvider<ApachePoolMeters> NO_POOL_METERS = provider(null);
  private static final ObjectProvider<OutboundInterceptorContribution> CONTRIBUTIONS =
      ContributionTestSupport.provider(new OutboundInterceptorContribution(
          (request, body, execution) -> execution.execute(request, body)));

  private static <T> ObjectProvider<T> provider(T value) {
    return new ObjectProvider<>() {
      @Override public T getObject() { return value; }
      @Override public T getIfAvailable() { return value; }
    };
  }

  static Stream<Arguments> factories() {
    return Stream.of(
        Arguments.of("jdk", new JdkRestClientFactory(SUPPORT_PROVIDER, RESILIENCE,
            NO_OBSERVATION, CONTRIBUTIONS)),
        Arguments.of("apache", new ApacheRestClientFactory(SUPPORT_PROVIDER, RESILIENCE,
            NO_POOL_METERS, NO_OBSERVATION, CONTRIBUTIONS)));
  }

  @BeforeAll
  static void startServer() {
    mockServer = ClientAndServer.startClientAndServer();
  }

  @AfterAll
  static void stopServer() {
    if (mockServer != null) mockServer.stop();
  }

  @BeforeEach
  void reset() {
    mockServer.reset();
  }

  private static ClientProperties properties() {
    var properties = new ClientProperties();
    properties.setBaseUrl("http://localhost:" + mockServer.getLocalPort());
    return properties;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("factories")
  void createRestClient_callsThroughTheBaseUrl(String name, RestClientFactory factory) {
    mockServer.when(request().withMethod("GET").withPath("/hello"), Times.once())
        .respond(response().withStatusCode(200).withBody("world"));

    var restClient = factory.createRestClient(name, properties());

    assertThat(restClient.get().uri("/hello").retrieve().body(String.class)).isEqualTo("world");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("factories")
  void createRestClient_mapsA404ToTheNotFoundException(String name, RestClientFactory factory) {
    mockServer.when(request().withMethod("GET").withPath("/missing"), Times.once())
        .respond(response().withStatusCode(404).withBody("no such thing"));

    var restClient = factory.createRestClient(name, properties());

    var response = restClient.get().uri("/missing").retrieve(); // lazy: no exchange yet

    assertThatThrownBy(() -> response.body(String.class))
        .isInstanceOf(OpenTmfClientNotFoundException.class)
        .hasMessageContaining("no such thing");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("factories")
  void createRestClient_followsPeerTextInMessage(String name, RestClientFactory factory) {
    mockServer.when(request().withMethod("GET").withPath("/broken"), Times.once())
        .respond(response().withStatusCode(500).withBody("secret peer text"));
    var properties = properties();
    properties.setPeerTextInMessage(PeerTextInMessage.NONE);

    var restClient = factory.createRestClient(name, properties);

    var response = restClient.get().uri("/broken").retrieve(); // lazy: no exchange yet

    assertThatThrownBy(() -> response.body(String.class))
        .isInstanceOf(OpenTmfClientResponseException.class)
        .isNotInstanceOf(OpenTmfClientNotFoundException.class)
        .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("secret peer text"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("factories")
  void createRestClient_carriesThePipelinesInterceptorsInTheSameOrder(String name,
      RestClientFactory factory) {
    var properties = properties();
    properties.getResilience().setEnabled(true);
    properties.setFixedHeaders(Map.of("X-Fixed", "1"));
    properties.setLoggingEnabled(true);

    var pipelineChain = ((AbstractRestClientFactory) factory)
        .pipeline(name, properties).interceptors().stream()
        .map(Object::getClass).toList();
    var clientChain = interceptorsOf(factory.createRestClient(name, properties)).stream()
        .map(Object::getClass).toList();

    assertThat(pipelineChain).hasSizeGreaterThanOrEqualTo(4);
    assertThat(clientChain).isEqualTo(pipelineChain);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("factories")
  void createRestClient_withBrokenCertificates_failsNamingTheClient(String name,
      RestClientFactory factory) {
    var properties = properties();
    var certificates = new ClientProperties.Certificates();
    var keyStore = new ClientProperties.Certificates.KeyStore();
    keyStore.setBase64Jks(Base64.getEncoder().encodeToString("bad".getBytes()));
    keyStore.setPassword("x");
    keyStore.setPkPassword("x");
    certificates.setKeyStore(keyStore);
    properties.setCertificates(certificates);

    assertThatThrownBy(() -> factory.createRestClient("broken-" + name, properties))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("broken-" + name);
  }

  @Test
  void theRegistrar_takesTheBackendsByTheirFactoryType() {
    mockServer.when(request().withMethod("GET").withPath("/old"), Times.once())
        .respond(response().withStatusCode(200).withBody("still here"));
    var context = new GenericApplicationContext();
    Map<String, RestClientFactory> backends = Map.of(
        "jdk", new JdkRestClientFactory(SUPPORT_PROVIDER, RESILIENCE, NO_OBSERVATION,
            CONTRIBUTIONS));
    ObjectProvider<TokenFetchMeters> noMeters = provider(null);

    var registrar = new RestClientRegistrar(context, noMeters, SUPPORT_PROVIDER, backends);

    assertThat(registrar.createRestClient("old", ClientType.JDK, properties()).get().uri("/old")
        .retrieve().body(String.class)).isEqualTo("still here");
    var properties = properties();
    assertThatThrownBy(() -> registrar.createRestClient("old", ClientType.APACHE, properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("requires client-type: APACHE");
    Map<String, RestClientFactory> foreign = Map.of("own",
        (clientId, clientProperties) -> RestClient.create());
    assertThatThrownBy(() -> new RestClientRegistrar(context, noMeters, SUPPORT_PROVIDER,
        foreign))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unknown RestClientFactory type");
  }

  static List<ClientHttpRequestInterceptor> interceptorsOf(RestClient restClient) {
    var captured = new ArrayList<ClientHttpRequestInterceptor>();
    restClient.mutate().requestInterceptors(captured::addAll);
    return captured;
  }
}
