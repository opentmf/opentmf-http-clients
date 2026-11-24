package org.opentmf.client.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;
import org.mockserver.model.HttpResponse;
import org.mockserver.verify.VerificationTimes;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
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
 * An API's {@code 401} that marks the bearer token invalid ({@code WWW-Authenticate: Bearer
 * error="invalid_token"}) drops the client's cached token, so the next {@code getToken()} mints a
 * fresh one. The refused call fails as before and is not retried; a {@code 401} without the marker
 * and a {@code 403} drop nothing.
 */
class RefusedTokenDropIT {

  private static final String INVALID_TOKEN = "Bearer error=\"invalid_token\", "
      + "error_description=\"The access token expired\"";

  /** The registry is there when the application declares a client of its own. */
  private static final String[] STATIC_CLIENT = {
      "opentmf.client-type=jdk", "opentmf.http-clients.static.base-url=http://localhost:9999"};

  private static ClientAndServer mockServer;
  private static String serverUrl;

  @Configuration(proxyBeanMethods = false)
  static class ReactiveInfraConfig {

    @Bean
    WebClient.Builder webClientBuilder() {
      return WebClient.builder();
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
    // the endpoint mints "first", then "second"
    mockServer.when(request().withMethod("POST").withPath("/token"), Times.once())
        .respond(token("first"));
    mockServer.when(request().withMethod("POST").withPath("/token"))
        .respond(token("second"));
    mockServer.when(request().withPath("/orders").withHeader("Authorization", "Bearer second"))
        .respond(response().withStatusCode(200).withBody("ok"));
  }

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(OpentmfHttpClientsAutoConfiguration.class))
      .withUserConfiguration(ReactiveInfraConfig.class);

  // --- static clients -----------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache"})
  void syncRestClient_aTokenRefusedAsInvalid_isDroppedAndTheNextCallMintsAFreshOne(String type) {
    refuse(401, INVALID_TOKEN);
    contextRunner.withPropertyValues(bearerClient("catalog", type)).run(context -> {
      var tokens = (SyncTokenService) context.getBean("catalogTokenService");
      var restClient = (RestClient) context.getBean("catalogRestClient");

      var refusal = catchThrowable(() -> callSync(restClient, tokens.getToken()));
      assertRefusedAsBefore(refusal, 401);

      assertThat(tokens.getToken()).isEqualTo("second");
      assertThat(callSync(restClient, tokens.getToken())).isEqualTo("ok");
      assertTokenCalls(2);
    });
  }

  @Test
  void reactiveWebClient_aTokenRefusedAsInvalid_isDroppedAndTheNextCallMintsAFreshOne() {
    refuse(401, INVALID_TOKEN);
    contextRunner.withPropertyValues(bearerClient("journal", "netty")).run(context -> {
      var tokens = (TokenService) context.getBean("journalTokenService");
      var webClient = (WebClient) context.getBean("journalWebClient");

      var refusal = catchThrowable(() -> callReactive(webClient, tokens.getToken().block()));
      assertRefusedAsBefore(refusal, 401);

      assertThat(tokens.getToken().block()).isEqualTo("second");
      assertThat(callReactive(webClient, tokens.getToken().block())).isEqualTo("ok");
      assertTokenCalls(2);
    });
  }

  // --- what drops nothing -------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {"401 Bearer realm=\"api\"", "401 ", "403 " + INVALID_TOKEN})
  void syncRefusalWithoutTheMarkerOrNot401_dropsNothing(String answer) {
    refuseAs(answer);
    contextRunner.withPropertyValues(bearerClient("catalog", "jdk")).run(context -> {
      var tokens = (SyncTokenService) context.getBean("catalogTokenService");
      var restClient = (RestClient) context.getBean("catalogRestClient");

      assertThat(catchThrowable(() -> callSync(restClient, tokens.getToken())))
          .isInstanceOf(OpenTmfClientResponseException.class);

      assertThat(tokens.getToken()).isEqualTo("first");
      assertTokenCalls(1);
    });
  }

  @ParameterizedTest
  @ValueSource(strings = {"401 Bearer realm=\"api\"", "401 ", "403 " + INVALID_TOKEN})
  void reactiveRefusalWithoutTheMarkerOrNot401_dropsNothing(String answer) {
    refuseAs(answer);
    contextRunner.withPropertyValues(bearerClient("journal", "netty")).run(context -> {
      var tokens = (TokenService) context.getBean("journalTokenService");
      var webClient = (WebClient) context.getBean("journalWebClient");

      assertThat(catchThrowable(() -> callReactive(webClient, tokens.getToken().block())))
          .isInstanceOf(OpenTmfClientResponseException.class);

      assertThat(tokens.getToken().block()).isEqualTo("first");
      assertTokenCalls(1);
    });
  }

  @Test
  void aRefusalOfAnotherToken_dropsNothing() {
    // a token this client never minted is refused: the client's own token stays
    refuse(401, INVALID_TOKEN, "Bearer stranger");
    contextRunner.withPropertyValues(bearerClient("catalog", "jdk")).run(context -> {
      var tokens = (SyncTokenService) context.getBean("catalogTokenService");
      var restClient = (RestClient) context.getBean("catalogRestClient");

      assertThat(tokens.getToken()).isEqualTo("first");
      assertThat(catchThrowable(() -> callSync(restClient, "stranger")))
          .isInstanceOf(OpenTmfClientResponseException.class);

      assertThat(tokens.getToken()).isEqualTo("first");
      assertTokenCalls(1);
    });
  }

  // --- dynamic clients ----------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {"JDK", "APACHE"})
  void dynamicSyncClient_aTokenRefusedAsInvalid_isDropped(String type) {
    refuse(401, INVALID_TOKEN);
    contextRunner.withPropertyValues(STATIC_CLIENT).run(context -> {
      var registry = context.getBean(HttpClientRegistry.class);
      var client = registry.getOrCreateRestClient("onedms", ClientType.valueOf(type),
          properties());

      var refusal = catchThrowable(
          () -> callSync(client.restClient(), client.tokenService().getToken()));
      assertRefusedAsBefore(refusal, 401);

      assertThat(client.tokenService().getToken()).isEqualTo("second");
      assertTokenCalls(2);
    });
  }

  @Test
  void dynamicReactiveClient_aTokenRefusedAsInvalid_isDropped() {
    refuse(401, INVALID_TOKEN);
    contextRunner.withPropertyValues(STATIC_CLIENT).run(context -> {
      var registry = context.getBean(HttpClientRegistry.class);
      var client = registry.getOrCreateReactive("asgw", properties());

      var refusal = catchThrowable(
          () -> callReactive(client.webClient(), client.tokenService().getToken().block()));
      assertRefusedAsBefore(refusal, 401);

      assertThat(client.tokenService().getToken().block()).isEqualTo("second");
      assertTokenCalls(2);
    });
  }

  // --- helpers ------------------------------------------------------------------------------

  private static String callSync(RestClient restClient, String token) {
    return restClient.get().uri("/orders").headers(h -> h.setBearerAuth(token))
        .retrieve().body(String.class);
  }

  private static String callReactive(WebClient webClient, String token) {
    return webClient.get().uri("/orders").headers(h -> h.setBearerAuth(token))
        .retrieve().bodyToMono(String.class).block();
  }

  /** The refused call fails as it always did: the library's exception with the API's status. */
  private static void assertRefusedAsBefore(Throwable refusal, int status) {
    assertThat(refusal).isInstanceOf(OpenTmfClientResponseException.class);
    assertThat(((OpenTmfClientResponseException) refusal).getStatusCode().value())
        .isEqualTo(status);
    // no retry inside the library: the API saw the refused token exactly once
    mockServer.verify(request().withPath("/orders").withHeader("Authorization", "Bearer first"),
        VerificationTimes.exactly(1));
  }

  private static void assertTokenCalls(int count) {
    mockServer.verify(request().withMethod("POST").withPath("/token"),
        VerificationTimes.exactly(count));
  }

  private static void refuse(int status, String challenge) {
    refuse(status, challenge, "Bearer first");
  }

  private static void refuse(int status, String challenge, String authorization) {
    HttpResponse answer = response().withStatusCode(status);
    if (!challenge.isEmpty()) {
      answer = answer.withHeader("WWW-Authenticate", challenge);
    }
    mockServer.when(request().withPath("/orders").withHeader("Authorization", authorization))
        .respond(answer);
  }

  /** {@code "<status> <challenge>"}; an empty challenge sends no header. */
  private static void refuseAs(String answer) {
    int space = answer.indexOf(' ');
    refuse(Integer.parseInt(answer.substring(0, space)), answer.substring(space + 1));
  }

  private static HttpResponse token(String value) {
    return response().withHeader("Content-Type", "application/json")
        .withBody("{\"access_token\":\"" + value + "\",\"expires_in\":3600}");
  }

  private static String[] bearerClient(String id, String clientType) {
    var prefix = "opentmf.http-clients." + id + ".";
    return new String[] {
        "opentmf.client-type=" + clientType,
        prefix + "base-url=" + serverUrl,
        prefix + "num-retries=0",
        prefix + "bearer-auth.token-url=" + serverUrl + "/token",
        prefix + "bearer-auth.client-id=client",
        prefix + "bearer-auth.client-secret=secret",
        prefix + "bearer-auth.form-data.grant_type=client_credentials"};
  }

  private static ClientProperties properties() {
    var bearer = new BearerAuthConfig();
    bearer.setTokenUrl(URI.create(serverUrl + "/token"));
    bearer.setClientId("client");
    bearer.setClientSecret("secret");
    bearer.setFormData(new HashMap<>(Map.of("grant_type", "client_credentials")));
    var properties = new ClientProperties();
    properties.setBaseUrl(serverUrl);
    properties.setNumRetries(0);
    properties.setBearerAuth(bearer);
    return properties;
  }
}
