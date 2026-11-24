package org.opentmf.client.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.integration.ClientAndServer;
import org.opentmf.client.common.model.AuthType;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.reactive.service.api.TokenService;
import org.opentmf.client.reactive.service.impl.NoOpTokenService;
import org.opentmf.client.rest.service.api.SyncTokenService;
import org.opentmf.client.rest.service.impl.NoOpSyncTokenService;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * A client without {@code basic-auth} and {@code bearer-auth} still has its
 * {@code <id>TokenService} bean: a no-op one whose token type and token are empty. Its auth type
 * is {@code NONE}, the way to tell it from a client with auth. The library's own clients never
 * put an {@code Authorization} header on an API call, whatever the auth type: the caller puts the
 * token on the request.
 */
class NoAuthClientIT {

  private static final String PREFIX = "opentmf.http-clients.backend.";

  @Configuration(proxyBeanMethods = false)
  static class ReactiveInfraConfig {

    @Bean
    WebClient.Builder webClientBuilder() {
      return WebClient.builder();
    }
  }

  private static ClientAndServer mockServer;
  private static boolean mockServerLogging;
  private static boolean mockServerSystemOut;

  @BeforeAll
  static void start() {
    mockServerLogging = ConfigurationProperties.disableLogging();
    mockServerSystemOut = ConfigurationProperties.disableSystemOut();
    ConfigurationProperties.disableLogging(true);
    ConfigurationProperties.disableSystemOut(true);
    mockServer = ClientAndServer.startClientAndServer();
  }

  @AfterAll
  static void stop() {
    mockServer.stop();
    ConfigurationProperties.disableLogging(mockServerLogging);
    ConfigurationProperties.disableSystemOut(mockServerSystemOut);
  }

  @BeforeEach
  void answer() {
    mockServer.reset();
    mockServer.when(request().withMethod("GET").withPath("/backend/thing"))
        .respond(response().withStatusCode(200)
            .withHeader("Content-Type", "application/json").withBody("{}"));
  }

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(OpentmfHttpClientsAutoConfiguration.class))
      .withUserConfiguration(ReactiveInfraConfig.class);

  private static String[] client(String clientType, String... more) {
    var base = new String[] {
        "opentmf.client-type=" + clientType,
        PREFIX + "base-url=http://localhost:" + mockServer.getLocalPort() + "/backend",
        PREFIX + "num-retries=0"};
    var all = new String[base.length + more.length];
    System.arraycopy(base, 0, all, 0, base.length);
    System.arraycopy(more, 0, all, base.length, more.length);
    return all;
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"jdk", "apache"})
  void syncClientWithoutAuth_hasANoOpTokenService_andAuthTypeNone(String clientType) {
    contextRunner.withPropertyValues(client(clientType)).run(context -> {
      var tokenService = context.getBean("backendTokenService", SyncTokenService.class);
      assertThat(tokenService).isInstanceOf(NoOpSyncTokenService.class);
      assertThat(tokenService.getTokenType()).isEmpty();
      assertThat(tokenService.getToken()).isEmpty();
      assertThat(authType(context)).isEqualTo(AuthType.NONE);
    });
  }

  @Test
  void reactiveClientWithoutAuth_hasANoOpTokenService_andAuthTypeNone() {
    contextRunner.withPropertyValues(client("netty")).run(context -> {
      var tokenService = context.getBean("backendTokenService", TokenService.class);
      assertThat(tokenService).isInstanceOf(NoOpTokenService.class);
      assertThat(tokenService.getTokenType()).isEmpty();
      assertThat(tokenService.getToken().block()).isEmpty();
      assertThat(authType(context)).isEqualTo(AuthType.NONE);
    });
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({"jdk, none", "apache, none", "netty, none",
      "jdk, basic", "apache, basic", "netty, basic"})
  void theLibrarysClient_sendsNoAuthorizationHeader(String clientType, String auth) {
    var properties = "basic".equals(auth)
        ? client(clientType, PREFIX + "basic-auth.username=user",
            PREFIX + "basic-auth.password=secret")
        : client(clientType);
    contextRunner.withPropertyValues(properties).run(context -> {
      if ("netty".equals(clientType)) {
        context.getBean("backendWebClient", WebClient.class).get().uri("/thing")
            .retrieve().toBodilessEntity().block();
      } else {
        context.getBean("backendRestClient", RestClient.class).get().uri("/thing")
            .retrieve().toBodilessEntity();
      }
      var received = mockServer.retrieveRecordedRequests(request().withPath("/backend/thing"));
      assertThat(received).hasSize(1);
      assertThat(received[0].containsHeader("Authorization")).isFalse();
    });
  }

  private static AuthType authType(AssertableApplicationContext context) {
    return context.getBean("backendClientProperties", ClientProperties.class).getAuthType();
  }
}
