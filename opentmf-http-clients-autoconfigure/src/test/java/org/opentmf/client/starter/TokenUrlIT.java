package org.opentmf.client.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.HttpError;
import org.opentmf.client.bearer.exception.BearerTokenException;
import org.opentmf.client.bearer.exception.BearerTokenResponseException;
import org.opentmf.client.bearer.exception.BearerTokenTransportException;
import org.opentmf.client.reactive.service.api.TokenService;
import org.opentmf.client.rest.service.api.SyncTokenService;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.zalando.logbook.autoconfigure.LogbookAutoConfiguration;

/**
 * A {@code bearer-auth.token-url} that cannot be called is refused at start by name, without
 * quoting it; a token URL with a user part keeps working where it works (no client type sends
 * the part), and the part never shows: not in a log line of the library or of Logbook at TRACE,
 * not in an exception message, not in an exception's token URL. A token URL with a query is
 * called with its query on every client type, and the library never shows the query; Logbook's
 * TRACE line of the token call hides it when {@code logbook.obfuscate.parameters} names it.
 *
 * <p>The user part below is a canary, not a credential. Released 2.5.1, measured: jdk and netty
 * mint with it (no Authorization header reaches the token endpoint) and print it in the INFO line
 * of every mint; apache fails with a {@code BearerTokenTransportException} whose message, and
 * its WARN line, print it.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
class TokenUrlIT {

  private static final String CANARY_PASSWORD = "canarypass7f3a";
  private static final String CANARY_USER_PART = "canaryuser:" + CANARY_PASSWORD;
  private static final String CANARY_QUERY_VALUE = "canaryquery9b2e";
  private static final String QUERY_PARAMETER = "client_secret";
  /** Logbook's query filter, set as the README says: the token URL's parameter, access_token. */
  private static final String LOGBOOK_QUERY_FILTER =
      "logbook.obfuscate.parameters=access_token," + QUERY_PARAMETER;
  private static final String PREFIX = "opentmf.http-clients.backend.";

  @Configuration(proxyBeanMethods = false)
  static class ReactiveInfraConfig {

    @Bean
    WebClient.Builder webClientBuilder() {
      return WebClient.builder();
    }
  }

  private static ClientAndServer mockServer;
  private static int port;
  private static boolean mockServerLogging;
  private static boolean mockServerSystemOut;
  private static final Map<String, Level> LEVELS = new HashMap<>();

  @BeforeAll
  static void start() {
    mockServerLogging = ConfigurationProperties.disableLogging();
    mockServerSystemOut = ConfigurationProperties.disableSystemOut();
    ConfigurationProperties.disableLogging(true);
    ConfigurationProperties.disableSystemOut(true);
    mockServer = ClientAndServer.startClientAndServer();
    port = mockServer.getLocalPort();
    for (var name : new String[] {"org.opentmf", "org.zalando.logbook"}) {
      var logger = (Logger) LoggerFactory.getLogger(name);
      LEVELS.put(name, logger.getLevel());
      logger.setLevel(Level.TRACE);
    }
  }

  @AfterAll
  static void stop() {
    mockServer.stop();
    ConfigurationProperties.disableLogging(mockServerLogging);
    ConfigurationProperties.disableSystemOut(mockServerSystemOut);
    LEVELS.forEach((name, level) -> ((Logger) LoggerFactory.getLogger(name)).setLevel(level));
  }

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(LogbookAutoConfiguration.class,
          OpentmfHttpClientsAutoConfiguration.class))
      .withUserConfiguration(ReactiveInfraConfig.class);

  private static String[] client(String clientType, String tokenUrl) {
    return new String[] {
        "opentmf.client-type=" + clientType,
        PREFIX + "base-url=http://localhost:" + port + "/backend",
        PREFIX + "num-retries=0",
        PREFIX + "bearer-auth.token-url=" + tokenUrl,
        PREFIX + "bearer-auth.form-data.grant_type=client_credentials"};
  }

  private static String canaryTokenUrl() {
    return "http://" + CANARY_USER_PART + "@localhost:" + port + "/token";
  }

  private static String queryCanaryTokenUrl() {
    return plainTokenUrl() + "?" + QUERY_PARAMETER + "=" + CANARY_QUERY_VALUE;
  }

  private static String plainTokenUrl() {
    return "http://localhost:" + port + "/token";
  }

  private static void identityProviderAnswers(int status, String body) {
    mockServer.reset();
    mockServer.when(request().withMethod("POST").withPath("/token"))
        .respond(response().withStatusCode(status)
            .withHeader("Content-Type", "application/json").withBody(body));
  }

  private static Throwable mint(AssertableApplicationContext context) {
    var tokenService = context.getBean("backendTokenService");
    return catchThrowable(() -> {
      if (tokenService instanceof TokenService reactive) {
        reactive.getToken().block(Duration.ofSeconds(10));
      } else {
        ((SyncTokenService) tokenService).getToken();
      }
    });
  }

  private static Throwable rootCause(Throwable failure) {
    var cause = failure;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource(delimiter = '|', value = {
      "jdk|htps://canaryuser:canarypass7f3a@localhost:1/token",
      "jdk|http://local host/token",
      "jdk|http:///token",
      "apache|htps://canaryuser:canarypass7f3a@localhost:1/token",
      "apache|http://local host/token",
      "apache|http:///token",
      "netty|htps://canaryuser:canarypass7f3a@localhost:1/token",
      "netty|http://local host/token",
      "netty|http:///token"})
  void aTokenUrlThatCannotBeCalled_isRefusedAtStart_byName_withoutQuotingIt(String clientType,
      String tokenUrl) {
    contextRunner.withPropertyValues(client(clientType, tokenUrl)).run(context -> {
      assertThat(context).hasFailed();
      assertThat(rootCause(context.getStartupFailure()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Client 'backend' has a bearer-auth.token-url that is not an absolute "
              + "http or https URL with a host.");
    });
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "netty"})
  void aUserPart_stillMints_andIsNeverShown(String clientType, CapturedOutput output) {
    identityProviderAnswers(200, "{\"access_token\":\"t\",\"expires_in\":3600}");
    int from = output.getAll().length();

    contextRunner.withPropertyValues(client(clientType, canaryTokenUrl()))
        .run(context -> assertThat(mint(context)).isNull());

    var logs = output.getAll().substring(from);
    assertThat(logs).contains("Bearer token minted from http://localhost:" + port + "/token")
        .doesNotContain(CANARY_PASSWORD);
    // no client type sends the part: no Authorization header reached the token endpoint
    var received = mockServer.retrieveRecordedRequests(request().withPath("/token"));
    assertThat(received).hasSize(1);
    assertThat(received[0].containsHeader("Authorization")).isFalse();
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({"jdk, 400", "jdk, 200", "netty, 400", "netty, 200"})
  void aUserPart_isNotInAnAnsweredFailure(String clientType, int status,
      CapturedOutput output) {
    identityProviderAnswers(status, status == 400 ? "{\"error\":\"invalid_client\"}" : "{}");
    int from = output.getAll().length();

    contextRunner.withPropertyValues(client(clientType, canaryTokenUrl())).run(context -> {
      var failure = mint(context);
      assertThat(failure).isInstanceOf(status == 400
          ? BearerTokenException.class : BearerTokenResponseException.class);
      assertThat(failure.getMessage()).contains("http://localhost:" + port + "/token")
          .doesNotContain(CANARY_PASSWORD);
      var tokenUrl = failure instanceof BearerTokenException e
          ? e.getTokenUrl() : ((BearerTokenResponseException) failure).getTokenUrl();
      assertThat(tokenUrl).hasNoUserInfo();
    });

    assertThat(output.getAll().substring(from)).doesNotContain(CANARY_PASSWORD);
  }

  @Test
  void apache_keepsFailingAsBefore_withoutTheUserPartInItsMessageOrLog(CapturedOutput output) {
    identityProviderAnswers(200, "{\"access_token\":\"t\",\"expires_in\":3600}");
    int from = output.getAll().length();

    contextRunner.withPropertyValues(client("apache", canaryTokenUrl())).run(context -> {
      var failure = mint(context);
      assertThat(failure).isInstanceOf(BearerTokenTransportException.class);
      assertThat(failure.getMessage()).doesNotContain(CANARY_PASSWORD)
          .contains("http://localhost:" + port + "/token");
      assertThat(((BearerTokenTransportException) failure).getTokenUrl()).hasNoUserInfo();
      assertThat(causeChain(failure)).doesNotContain(CANARY_PASSWORD);
    });

    assertThat(output.getAll().substring(from)).doesNotContain(CANARY_PASSWORD);
    assertThat(mockServer.retrieveRecordedRequests(request().withPath("/token"))).isEmpty();
  }

  /** Every message of the failure's cause chain, the failure's own excluded. */
  private static String causeChain(Throwable failure) {
    var chain = new StringBuilder();
    for (Throwable c = failure.getCause(); c != null; c = c.getCause()) {
      chain.append(c.getClass().getName()).append(": ").append(c.getMessage()).append('\n');
    }
    return chain.toString();
  }

  /**
   * A transport failure of a URL with a user part or a query chains no cause, since the HTTP
   * client's own exceptions quote the URL with them; its message keeps the cause chain without
   * them. A URL without either chains the cause as before.
   */
  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({"jdk, plain", "jdk, userPart", "jdk, query", "apache, plain", "apache, query",
      "apache, userPart", "netty, plain", "netty, userPart", "netty, query"})
  void aTransportFailure_chainsTheCause_onlyWithoutAUserPartOrQuery(String clientType,
      String url, CapturedOutput output) {
    mockServer.reset();
    mockServer.when(request().withMethod("POST").withPath("/token"))
        .error(HttpError.error().withDropConnection(true));
    var tokenUrl = switch (url) {
      case "userPart" -> canaryTokenUrl();
      case "query" -> queryCanaryTokenUrl();
      default -> plainTokenUrl();
    };
    int from = output.getAll().length();

    contextRunner.withPropertyValues(client(clientType, tokenUrl))
        .withPropertyValues(LOGBOOK_QUERY_FILTER).run(context -> {
          var failure = mint(context);
          assertThat(failure).isInstanceOf(BearerTokenTransportException.class);
          assertThat(failure.getMessage()).doesNotContain(CANARY_PASSWORD)
              .doesNotContain(CANARY_QUERY_VALUE)
              .contains("failed at the transport level after 2 attempts: ")
              .containsPattern("[A-Z][A-Za-z]+(Exception|Error)");
          if ("plain".equals(url)) {
            assertThat(failure.getCause()).isNotNull();
          } else {
            assertThat(failure.getCause()).isNull();
          }
          assertThat(((BearerTokenTransportException) failure).getTokenUrl())
              .hasToString(plainTokenUrl());
          assertThat(causeChain(failure)).doesNotContain(CANARY_PASSWORD)
              .doesNotContain(CANARY_QUERY_VALUE);
        });

    assertThat(output.getAll().substring(from)).doesNotContain(CANARY_PASSWORD)
        .doesNotContain(CANARY_QUERY_VALUE);
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache", "netty"})
  void aQuery_isSentAsConfigured_andIsNeverShown(String clientType, CapturedOutput output) {
    identityProviderAnswers(200, "{\"access_token\":\"t\",\"expires_in\":3600}");
    int from = output.getAll().length();

    contextRunner.withPropertyValues(client(clientType, queryCanaryTokenUrl()))
        .withPropertyValues(LOGBOOK_QUERY_FILTER)
        .run(context -> assertThat(mint(context)).isNull());

    var logs = output.getAll().substring(from);
    assertThat(logs).contains("Bearer token minted from " + plainTokenUrl() + " (")
        .doesNotContain(CANARY_QUERY_VALUE);
    var received = mockServer.retrieveRecordedRequests(request().withPath("/token"));
    assertThat(received).hasSize(1);
    assertThat(received[0].getFirstQueryStringParameter(QUERY_PARAMETER))
        .isEqualTo(CANARY_QUERY_VALUE);
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({"jdk, 401", "jdk, 200", "apache, 401", "apache, 200", "netty, 401",
      "netty, 200"})
  void aQuery_isNotInAnAnsweredFailure(String clientType, int status, CapturedOutput output) {
    identityProviderAnswers(status, status == 401 ? "{\"error\":\"invalid_client\"}" : "{}");
    int from = output.getAll().length();

    contextRunner.withPropertyValues(client(clientType, queryCanaryTokenUrl()))
        .withPropertyValues(LOGBOOK_QUERY_FILTER).run(context -> {
          var failure = mint(context);
          assertThat(failure).isInstanceOf(status == 401
              ? BearerTokenException.class : BearerTokenResponseException.class);
          assertThat(failure.getMessage()).contains(plainTokenUrl())
              .doesNotContain(CANARY_QUERY_VALUE);
          var tokenUrl = failure instanceof BearerTokenException e
              ? e.getTokenUrl() : ((BearerTokenResponseException) failure).getTokenUrl();
          assertThat(tokenUrl).hasToString(plainTokenUrl());
          assertThat(causeChain(failure)).doesNotContain(CANARY_QUERY_VALUE);
        });

    assertThat(output.getAll().substring(from)).doesNotContain(CANARY_QUERY_VALUE);
  }
}
