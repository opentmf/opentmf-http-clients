package org.opentmf.client.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.integration.ClientAndServer;
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
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.zalando.logbook.autoconfigure.LogbookAutoConfiguration;

/**
 * Logbook, with its default configuration, logs a token call without the token endpoint's
 * response body on every client type: the marker stands in its place, the status and headers
 * are logged as before, the request is logged as Logbook logs it (its default filters mask the
 * form's {@code client_secret}). The client's own calls keep their bodies, and
 * {@code bearer-auth.log-response-body: true} brings the token body back.
 *
 * <p>The identity provider echoes the form's {@code client_secret}, in a 400 and in a 200.
 * Released 2.5.1, measured: the TRACE response line of the token call carries that echo.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
class TokenCallLogbookIT {

  private static final String SECRET = "form-client-secret-4711";
  private static final String TOKEN = "minted-access-token-0815";
  private static final String ECHO_400 = "{\"error\":\"invalid_client\",\"client_secret\":\""
      + SECRET + "\"}";
  private static final String ECHO_200 = "{\"access_token\":\"" + TOKEN
      + "\",\"expires_in\":3600,\"token_type\":\"Bearer\",\"client_secret\":\"" + SECRET + "\"}";
  private static final String BUSINESS_BODY = "business-answer-body";

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
  private static Level logbookLevel;

  @BeforeAll
  static void start() {
    // the stand-in identity provider's own request log quotes every body: silenced
    mockServerLogging = ConfigurationProperties.disableLogging();
    mockServerSystemOut = ConfigurationProperties.disableSystemOut();
    ConfigurationProperties.disableLogging(true);
    ConfigurationProperties.disableSystemOut(true);
    mockServer = ClientAndServer.startClientAndServer();
    baseUrl = "http://localhost:" + mockServer.getLocalPort();
    var logbook = (Logger) LoggerFactory.getLogger("org.zalando.logbook");
    logbookLevel = logbook.getLevel();
    logbook.setLevel(Level.TRACE);
  }

  @AfterAll
  static void stop() {
    mockServer.stop();
    ConfigurationProperties.disableLogging(mockServerLogging);
    ConfigurationProperties.disableSystemOut(mockServerSystemOut);
    ((Logger) LoggerFactory.getLogger("org.zalando.logbook")).setLevel(logbookLevel);
  }

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(LogbookAutoConfiguration.class,
          OpentmfHttpClientsAutoConfiguration.class))
      .withUserConfiguration(ReactiveInfraConfig.class);

  private static void identityProviderAnswers(int status, String body) {
    mockServer.reset();
    mockServer.when(request().withMethod("POST").withPath("/token"))
        .respond(response().withStatusCode(status)
            .withHeader("Content-Type", "application/json")
            .withHeader("X-Idp-Trace", "idp-header-kept")
            .withBody(body));
    mockServer.when(request().withMethod("GET").withPath("/backend/api"))
        .respond(response().withBody(BUSINESS_BODY));
  }

  /** The client secret in the form, not as Basic auth, so the request body carries it. */
  private static String[] client(String clientType, String... extra) {
    var prefix = "opentmf.http-clients.backend.";
    var base = List.of(
        "opentmf.client-type=" + clientType,
        prefix + "base-url=" + baseUrl + "/backend",
        prefix + "num-retries=0",
        prefix + "bearer-auth.token-url=" + baseUrl + "/token",
        prefix + "bearer-auth.form-data.grant_type=client_credentials",
        prefix + "bearer-auth.form-data.client_id=client",
        prefix + "bearer-auth.form-data.client_secret=" + SECRET);
    var all = new ArrayList<>(base);
    Arrays.stream(extra).map(e -> prefix + e).forEach(all::add);
    return all.toArray(String[]::new);
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

  private static String businessCall(AssertableApplicationContext context) {
    if (context.containsBean("backendWebClient")) {
      return ((WebClient) context.getBean("backendWebClient")).get().uri("/api").retrieve()
          .bodyToMono(String.class).block(Duration.ofSeconds(10));
    }
    return ((RestClient) context.getBean("backendRestClient")).get().uri("/api").retrieve()
        .body(String.class);
  }

  /** The Logbook lines captured since {@code from}. */
  private static List<String> logbookLines(CapturedOutput output, int from) {
    return output.getAll().substring(from).lines()
        .filter(line -> line.contains("org.zalando.logbook")).toList();
  }

  private static String tokenResponseLine(List<String> lines) {
    return lines.stream()
        .filter(l -> l.contains("\"type\":\"response\"") && l.contains("idp-header-kept"))
        .findFirst().orElseThrow(() -> new AssertionError("no token response line in " + lines));
  }

  private static String tokenRequestLine(List<String> lines) {
    return lines.stream()
        .filter(l -> l.contains("\"type\":\"request\"") && l.contains("/token"))
        .findFirst().orElseThrow(() -> new AssertionError("no token request line in " + lines));
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({"jdk, 400", "jdk, 200", "apache, 400", "apache, 200", "netty, 400",
      "netty, 200"})
  void theTokenCallsResponseBody_isWithheldFromTheLog(String clientType, int status,
      CapturedOutput output) {
    identityProviderAnswers(status, status == 200 ? ECHO_200 : ECHO_400);
    int from = output.getAll().length();

    contextRunner.withPropertyValues(client(clientType)).run(context -> {
      var failure = mint(context);
      assertThat(failure).as("the mint").satisfies(f -> {
        if (status == 200) {
          assertThat(f).isNull();
        } else {
          assertThat(f).isNotNull();
        }
      });
      assertThat(businessCall(context)).isEqualTo(BUSINESS_BODY);
    });

    var lines = logbookLines(output, from);
    assertThat(String.join("\n", lines)).doesNotContain(SECRET).doesNotContain(TOKEN);
    // the response: the marker for the body, the status and headers as before
    var response = tokenResponseLine(lines);
    assertThat(response).contains(TokenCallLogbook.WITHHELD)
        .contains("\"status\":" + status)
        .contains("idp-header-kept");
    // the request: logged by Logbook as before, its default filters masking the form's secret
    assertThat(tokenRequestLine(lines)).contains("client_secret=XXX");
    // the client's own call keeps its body
    assertThat(lines).anyMatch(l -> l.contains(BUSINESS_BODY));
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache", "netty"})
  void logResponseBody_bringsTheTokenBodyBack(String clientType, CapturedOutput output) {
    identityProviderAnswers(400, ECHO_400);
    int from = output.getAll().length();

    contextRunner.withPropertyValues(client(clientType, "bearer-auth.log-response-body=true"))
        .run(context -> assertThat(mint(context)).isNotNull());

    var response = tokenResponseLine(logbookLines(output, from));
    assertThat(response).contains(SECRET).doesNotContain(TokenCallLogbook.WITHHELD);
  }
}
