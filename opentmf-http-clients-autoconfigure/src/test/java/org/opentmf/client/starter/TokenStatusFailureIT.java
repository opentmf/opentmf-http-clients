package org.opentmf.client.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.integration.ClientAndServer;
import org.opentmf.client.bearer.exception.BearerTokenException;
import org.opentmf.client.bearer.exception.BearerTokenResponseException;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.reactive.service.api.TokenService;
import org.opentmf.client.rest.service.api.SyncTokenService;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * An error status from the token endpoint surfaces as the token call's own failure, a
 * {@link BearerTokenException}, on every client type: the status, the response headers and the
 * retries of a released version kept, the identity provider's body withheld from the exception
 * and from every log line, a 404 included (it says the token URL is wrong, not that a resource
 * is absent).
 *
 * <p>The identity provider below answers with the error code {@value #OAUTH_ERROR}, a
 * description, and an echo of a credential, as a provider that echoes the request form would.
 * Released 2.5.1, measured with the same cells: the plain {@code OpenTmfClientResponseException}
 * ({@code OpenTmfClientNotFoundException} for the 404), its {@code getResponseBody()} the whole
 * body, the echo included, its message {@code "HTTP 400 Bad Request: <description>"}; the same
 * status, headers, {@code Retry-After} and token-call count as asserted here.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
class TokenStatusFailureIT {

  private static final String OAUTH_ERROR = "invalid_client";
  private static final String DESCRIPTION = "Invalid client credentials";
  private static final String ECHO = "ECHOED-CLIENT-SECRET";
  private static final String BODY = "{\"error\":\"" + OAUTH_ERROR + "\",\"error_description\":\""
      + DESCRIPTION + "\",\"client_secret\":\"" + ECHO + "\"}";

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
    // the stand-in identity provider's own request log quotes the body: silenced, so that what
    // is captured below is what the client logs
    mockServerLogging = ConfigurationProperties.disableLogging();
    mockServerSystemOut = ConfigurationProperties.disableSystemOut();
    ConfigurationProperties.disableLogging(true);
    ConfigurationProperties.disableSystemOut(true);
    mockServer = ClientAndServer.startClientAndServer();
    baseUrl = "http://localhost:" + mockServer.getLocalPort();
  }

  @AfterAll
  static void stopMockServer() {
    mockServer.stop();
    ConfigurationProperties.disableLogging(mockServerLogging);
    ConfigurationProperties.disableSystemOut(mockServerSystemOut);
  }

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(OpentmfHttpClientsAutoConfiguration.class))
      .withUserConfiguration(ReactiveInfraConfig.class);

  private static void identityProviderAnswers(int status) {
    mockServer.reset();
    mockServer.when(request().withMethod("POST").withPath("/token"))
        .respond(response().withStatusCode(status)
            .withHeader("Content-Type", "application/json")
            .withHeader("Retry-After", "1")
            .withHeader("X-Idp-Trace", "kept")
            .withBody(BODY));
  }

  private static String[] client(String clientType) {
    var prefix = "opentmf.http-clients.backend.";
    return new String[] {
        "opentmf.client-type=" + clientType,
        prefix + "base-url=" + baseUrl + "/backend",
        prefix + "num-retries=1",
        prefix + "retry-wait-duration=10ms",
        prefix + "logging-enabled=false",
        prefix + "bearer-auth.token-url=" + baseUrl + "/token",
        prefix + "bearer-auth.client-id=client",
        prefix + "bearer-auth.client-secret=secret",
        prefix + "bearer-auth.form-data.grant_type=client_credentials"};
  }

  private static Throwable failedMint(AssertableApplicationContext context) {
    var tokenService = context.getBean("backendTokenService");
    return catchThrowable(() -> {
      if (tokenService instanceof TokenService reactive) {
        reactive.getToken().block(Duration.ofSeconds(10));
      } else {
        ((SyncTokenService) tokenService).getToken();
      }
    });
  }

  private static int tokenCalls() {
    return mockServer.retrieveRecordedRequests(request().withPath("/token")).length;
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({
      "jdk,    400, Bad Request",
      "jdk,    404, Not Found",
      "jdk,    410, Gone",
      "jdk,    503, Service Unavailable",
      "apache, 400, Bad Request",
      "apache, 404, Not Found",
      "apache, 410, Gone",
      "apache, 503, Service Unavailable",
      "netty,  400, Bad Request",
      "netty,  404, Not Found",
      "netty,  410, Gone",
      "netty,  503, Service Unavailable"})
  void anErrorStatus_isTheTokenCallsOwnFailure_withItsBodyWithheld(String clientType, int status,
      String reason, CapturedOutput output) {
    identityProviderAnswers(status);
    int logStart = output.getAll().length();

    contextRunner.withPropertyValues(client(clientType)).run(context -> {
      var failure = failedMint(context);

      // the type: the token call's own, still in the response family, never the not-found one
      assertThat(failure).isExactlyInstanceOf(BearerTokenException.class)
          .isInstanceOf(OpenTmfClientResponseException.class)
          .isNotInstanceOf(OpenTmfClientNotFoundException.class)
          .isNotInstanceOf(BearerTokenResponseException.class);
      var tokenFailure = (BearerTokenException) failure;

      // kept: the status, the headers, a parsed Retry-After where the status is retryable
      assertThat(tokenFailure.getRawStatusCode()).isEqualTo(status);
      assertThat(tokenFailure.getTokenUrl()).isEqualTo(URI.create(baseUrl + "/token"));
      assertThat(tokenFailure.getHeaders()).isNotNull();
      assertThat(tokenFailure.getHeaders().getFirst("X-Idp-Trace")).isEqualTo("kept");
      assertThat(tokenFailure.getRetryAfter())
          .isEqualTo(status == 503 ? Duration.ofSeconds(1) : null);

      // withheld: the body; only the OAuth error code is read from it
      assertThat(tokenFailure.getResponseBody()).isNull();
      assertThat(tokenFailure.getOauthError()).isEqualTo(OAUTH_ERROR);
      assertThat(tokenFailure.getMessage()).isEqualTo("Bearer token request to " + baseUrl
          + "/token answered " + status + " " + reason + " (error=" + OAUTH_ERROR + ")");
      assertThat(tokenFailure.getCause()).isNull();

      // the retry count of the released version: a 503 once more (num-retries 1), else none
      assertThat(tokenCalls()).isEqualTo(status == 503 ? 2 : 1);
    });

    var logs = output.getAll().substring(logStart);
    assertThat(logs).doesNotContain(ECHO).doesNotContain(DESCRIPTION);
  }
}
