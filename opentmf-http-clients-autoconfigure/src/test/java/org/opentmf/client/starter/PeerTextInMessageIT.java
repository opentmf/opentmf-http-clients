package org.opentmf.client.starter;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.integration.ClientAndServer;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.model.PeerTextInMessage;
import org.opentmf.client.starter.rest.RestClientRegistrar;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * {@code peer-text-in-message} decides how much of a peer's error text reaches the message of the
 * client exception, on every client type and through every client the library builds
 * ({@code RestTemplate} and {@code RestClient} for jdk and apache, {@code WebClient} for netty).
 * Unset and {@code whole} give today's message, a field of any length taken whole;
 * {@code capped} cuts the peer's text at {@code ErrorBodyExtractor.MAX_BODY_LENGTH} characters;
 * {@code none} keeps the status and reason only. In every value the exception keeps the whole
 * body, the status, the headers, {@code Retry-After} and the 404 subtype.
 */
class PeerTextInMessageIT {

  private static final int CAP = 2000;
  private static final String LONG_DETAIL = "D".repeat(3000);
  private static final String LONG_FIELD_BODY =
      "{\"detail\":\"" + LONG_DETAIL + "\",\"title\":\"Bad Thing\"}";
  /** A non-string field: the message takes its JSON text, which Jackson writes compact. */
  private static final String ERROR_OBJECT = IntStream.range(0, 300)
      .mapToObj("\"entry-%04d\""::formatted)
      .collect(Collectors.joining(",", "{\"items\":[", "]}"));
  private static final String OBJECT_FIELD_BODY = "{\"error\":" + ERROR_OBJECT + "}";
  private static final String NOT_FOUND_BODY =
      "{\"detail\":\"Party 123 does not exist\",\"title\":\"Not Found\"}";

  private static final String BAD_REQUEST = "HTTP 400 Bad Request";
  private static final String UNAVAILABLE = "HTTP 503 Service Unavailable";
  private static final String NOT_FOUND = "HTTP 404 Not Found";

  @Configuration(proxyBeanMethods = false)
  static class ReactiveInfraConfig {

    @Bean
    WebClient.Builder webClientBuilder() {
      return WebClient.builder();
    }
  }

  private static ClientAndServer mockServer;
  private static String baseUrl;
  private static boolean mockServerLogging;
  private static boolean mockServerSystemOut;

  @BeforeAll
  static void startMockServer() {
    mockServerLogging = ConfigurationProperties.disableLogging();
    mockServerSystemOut = ConfigurationProperties.disableSystemOut();
    ConfigurationProperties.disableLogging(true);
    ConfigurationProperties.disableSystemOut(true);
    mockServer = ClientAndServer.startClientAndServer();
    baseUrl = "http://localhost:" + mockServer.getLocalPort();
    peerAnswers("/backend/long-field", 400, LONG_FIELD_BODY);
    peerAnswers("/backend/object-field", 503, OBJECT_FIELD_BODY);
    peerAnswers("/backend/missing", 404, NOT_FOUND_BODY);
  }

  @AfterAll
  static void stopMockServer() {
    mockServer.stop();
    ConfigurationProperties.disableLogging(mockServerLogging);
    ConfigurationProperties.disableSystemOut(mockServerSystemOut);
  }

  private static void peerAnswers(String path, int status, String body) {
    mockServer.when(request().withMethod("GET").withPath(path))
        .respond(response().withStatusCode(status)
            .withHeader("Content-Type", "application/json")
            .withHeader("Retry-After", "1")
            .withHeader("X-Peer-Trace", "kept")
            .withBody(body));
  }

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(OpentmfHttpClientsAutoConfiguration.class))
      .withUserConfiguration(ReactiveInfraConfig.class);

  /** What the message is in each value; {@code ""} stands for unset. */
  private record Expected(String value, String longField, String objectField, String notFound) {
  }

  private static final Expected UNSET = new Expected("",
      BAD_REQUEST + ": " + LONG_DETAIL + " — Bad Thing",
      UNAVAILABLE + ": " + ERROR_OBJECT,
      NOT_FOUND + ": Party 123 does not exist — Not Found");
  private static final Expected WHOLE = new Expected("whole",
      UNSET.longField(), UNSET.objectField(), UNSET.notFound());
  private static final Expected CAPPED = new Expected("capped",
      BAD_REQUEST + ": " + LONG_DETAIL.substring(0, CAP) + "...",
      UNAVAILABLE + ": " + ERROR_OBJECT.substring(0, CAP) + "...",
      UNSET.notFound());
  private static final Expected NONE = new Expected("none",
      BAD_REQUEST, UNAVAILABLE, NOT_FOUND);

  static Stream<Arguments> cells() {
    var cells = new ArrayList<Arguments>();
    for (String clientType : List.of("jdk", "apache", "netty")) {
      for (Expected expected : List.of(UNSET, WHOLE, CAPPED, NONE)) {
        cells.add(Arguments.of(clientType,
            expected.value().isEmpty() ? "unset" : expected.value(), expected));
      }
    }
    return cells.stream();
  }

  private static String[] client(String clientType, String value) {
    var prefix = "opentmf.http-clients.backend.";
    var properties = new ArrayList<>(List.of(
        "opentmf.client-type=" + clientType,
        prefix + "base-url=" + baseUrl + "/backend",
        prefix + "logging-enabled=false"));
    if (!value.isEmpty()) {
      properties.add(prefix + "peer-text-in-message=" + value);
    }
    return properties.toArray(String[]::new);
  }

  /** Every client of the given type, each answering one GET with its failure. */
  private static List<Throwable> failedGets(AssertableApplicationContext context,
      String clientType, String path) {
    if (clientType.equals("netty")) {
      var webClient = context.getBean("backendWebClient", WebClient.class);
      return List.of(catchThrowable(() -> webClient.get().uri(path).retrieve()
          .bodyToMono(String.class).block(Duration.ofSeconds(10))));
    }
    var restTemplate = context.getBean("backendRestTemplate", RestTemplate.class);
    var restClient = context.getBean("backendRestClient", RestClient.class);
    return List.of(
        catchThrowable(() -> restTemplate.getForObject(path, String.class)),
        catchThrowable(() -> restClient.get().uri(path).retrieve().body(String.class)));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("cells")
  void theMessageCarriesThePeersTextAsConfigured_andEverythingElseIsKept(String clientType,
      String label, Expected expected) {
    contextRunner.withPropertyValues(client(clientType, expected.value())).run(context -> {
      assertThat(context).as(label).hasNotFailed();

      for (Throwable failure : failedGets(context, clientType, "/long-field")) {
        assertFailure(failure, 400, expected.longField(), LONG_FIELD_BODY, null);
      }
      for (Throwable failure : failedGets(context, clientType, "/object-field")) {
        assertFailure(failure, 503, expected.objectField(), OBJECT_FIELD_BODY,
            Duration.ofSeconds(1));
      }
      for (Throwable failure : failedGets(context, clientType, "/missing")) {
        assertThat(failure).isExactlyInstanceOf(OpenTmfClientNotFoundException.class);
        assertFailure(failure, 404, expected.notFound(), NOT_FOUND_BODY, null);
      }
    });
  }

  /**
   * The registrar's {@code RestClient} status handler on its own (the {@code RestTemplate}'s
   * handler passes every answer through): the overload taking the client's properties follows
   * its value, the one without them keeps today's message.
   */
  @Test
  void theRegistrarsRestClient_followsTheClientsValue_andTodaysWithoutIt() {
    contextRunner.withPropertyValues(client("jdk", "")).run(context -> {
      var registrar = context.getBean(RestClientRegistrar.class);
      var restTemplate = new RestTemplate();
      restTemplate.setErrorHandler(new NoOpResponseErrorHandler());
      var properties = new ClientProperties();
      properties.setPeerTextInMessage(PeerTextInMessage.NONE);
      var uri = baseUrl + "/backend/long-field";

      var none = catchThrowable(() -> registrar.createRestClient(restTemplate, properties)
          .get().uri(uri).retrieve().body(String.class));
      assertFailure(none, 400, NONE.longField(), LONG_FIELD_BODY, null);

      var today = catchThrowable(() -> registrar.createRestClient(restTemplate)
          .get().uri(uri).retrieve().body(String.class));
      assertFailure(today, 400, UNSET.longField(), LONG_FIELD_BODY, null);
    });
  }

  private static void assertFailure(Throwable failure, int status, String message, String body,
      Duration retryAfter) {
    assertThat(failure).isInstanceOf(OpenTmfClientResponseException.class);
    var e = (OpenTmfClientResponseException) failure;
    assertThat(e.getMessage()).isEqualTo(message);
    assertThat(e.getRawStatusCode()).isEqualTo(status);
    assertThat(e.getResponseBody()).isEqualTo(body);
    assertThat(requireNonNull(e.getHeaders()).getFirst("X-Peer-Trace")).isEqualTo("kept");
    assertThat(e.getRetryAfter()).isEqualTo(retryAfter);
    if (status != 404) {
      assertThat(e).isNotInstanceOf(OpenTmfClientNotFoundException.class);
    }
  }
}
