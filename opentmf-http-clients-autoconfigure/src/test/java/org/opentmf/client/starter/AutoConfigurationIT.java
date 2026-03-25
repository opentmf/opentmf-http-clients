package org.opentmf.client.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.reactive.service.api.TokenService;
import org.opentmf.client.rest.service.api.SyncTokenService;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.zalando.logbook.Logbook;
import reactor.test.StepVerifier;

@ExtendWith(OutputCaptureExtension.class)
class AutoConfigurationIT {

  @Configuration(proxyBeanMethods = false)
  static class ReactiveInfraConfig {

    @Bean
    WebClient.Builder webClientBuilder() {
      return WebClient.builder();
    }

    @Bean
    Logbook logbook() {
      return Logbook.create();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class WebClientBuilderOnlyConfig {
    @Bean
    WebClient.Builder webClientBuilder() {
      return WebClient.builder();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class LogbookBeanConfig {
    @Bean
    Logbook logbook() {
      return Logbook.create();
    }
  }

  private static ClientAndServer mockServer;
  private static String tokenUrl;

  @BeforeAll
  static void startMockServer() {
    mockServer = ClientAndServer.startClientAndServer();
    tokenUrl = "http://localhost:" + mockServer.getLocalPort() + "/token";

    mockServer.when(request().withMethod("POST").withPath("/token"))
        .respond(response().withStatusCode(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"access_token\":\"test-bearer-token\",\"expires_in\":3600,\"token_type\":\"Bearer\"}"));
  }

  @AfterAll
  static void stopMockServer() {
    if (mockServer != null) mockServer.stop();
  }

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(OpentmfHttpClientsAutoConfiguration.class))
      .withUserConfiguration(ReactiveInfraConfig.class);

  @Test
  void jdkClient_noAuth_registersBeans() {
    contextRunner
        .withPropertyValues(
            "opentmf.client-type=jdk",
            "opentmf.http-clients.catalog.base-url=http://localhost:9999")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.containsBean("catalogRestTemplate")).isTrue();
          assertThat(context.containsBean("catalogRestClient")).isTrue();
          assertThat(context.containsBean("catalogTokenService")).isTrue();
          assertThat(context.containsBean("catalogClientProperties")).isTrue();
          assertThat(context.containsBean("opentmfHttpClientsStarter")).isTrue();

          var restTemplate = (RestTemplate) context.getBean("catalogRestTemplate");
          assertThat(restTemplate).isNotNull();

          var restClient = (RestClient) context.getBean("catalogRestClient");
          assertThat(restClient).isNotNull();

          var tokenService = (SyncTokenService) context.getBean("catalogTokenService");
          assertThat(tokenService.getToken()).isEmpty();
        });
  }

  @Test
  void jdkClient_basicAuth_registersBasicTokenService() {
    contextRunner
        .withPropertyValues(
            "opentmf.client-type=jdk",
            "opentmf.http-clients.myapi.base-url=http://localhost:9999",
            "opentmf.http-clients.myapi.basic-auth.username=user",
            "opentmf.http-clients.myapi.basic-auth.password=pass")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var tokenService = (SyncTokenService) context.getBean("myapiTokenService");
          assertThat(tokenService.getToken()).isNotEmpty();
        });
  }

  @Test
  void apacheClient_registersBeans() {
    contextRunner
        .withPropertyValues(
            "opentmf.client-type=apache",
            "opentmf.http-clients.svc.base-url=http://localhost:9999")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.containsBean("svcRestTemplate")).isTrue();
          assertThat(context.containsBean("svcRestClient")).isTrue();
          assertThat(context.containsBean("svcTokenService")).isTrue();
        });
  }

  @Test
  void multipleClients_mixedTypes() {
    contextRunner
        .withPropertyValues(
            "opentmf.client-type=jdk",
            "opentmf.http-clients.alpha.base-url=http://localhost:9999",
            "opentmf.http-clients.beta.base-url=http://localhost:9998",
            "opentmf.http-clients.beta.client-type=apache")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.containsBean("alphaRestTemplate")).isTrue();
          assertThat(context.containsBean("alphaRestClient")).isTrue();
          assertThat(context.containsBean("betaRestTemplate")).isTrue();
          assertThat(context.containsBean("betaRestClient")).isTrue();
        });
  }

  @Test
  void perClientClientType_overridesGlobal() {
    contextRunner
        .withPropertyValues(
            "opentmf.client-type=jdk",
            "opentmf.http-clients.svc.base-url=http://localhost:9999",
            "opentmf.http-clients.svc.client-type=apache")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.containsBean("svcRestTemplate")).isTrue();
        });
  }

  @Test
  void bearerAuth_withMock_registersMockTokenService() {
    contextRunner
        .withPropertyValues(
            "opentmf.client-type=jdk",
            "opentmf.http-clients.svc.base-url=http://localhost:9999",
            "opentmf.http-clients.svc.bearer-auth.use-mock=true",
            "opentmf.http-clients.svc.bearer-auth.token-url=http://localhost:9999/token",
            "opentmf.http-clients.svc.bearer-auth.form-data.grant_type=client_credentials")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var tokenService = (SyncTokenService) context.getBean("svcTokenService");
          assertThat(tokenService.getToken()).isNotEmpty();
        });
  }

  @Test
  void resolveClientType_synonyms() {
    contextRunner
        .withPropertyValues(
            "opentmf.client-type=rest",
            "opentmf.http-clients.svc.base-url=http://localhost:9999")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.containsBean("svcRestTemplate")).isTrue();
        });
  }

  @Test
  void nettyClient_registersBeans() {
    contextRunner
        .withPropertyValues(
            "opentmf.client-type=netty",
            "opentmf.http-clients.reactive-svc.base-url=http://localhost:9999")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.containsBean("reactive-svcWebClient")).isTrue();
          assertThat(context.containsBean("reactive-svcTokenService")).isTrue();
          assertThat(context.containsBean("reactive-svcClientProperties")).isTrue();
        });
  }

  @Test
  void nettyClient_basicAuth_registersBasicTokenService() {
    contextRunner
        .withPropertyValues(
            "opentmf.client-type=netty",
            "opentmf.http-clients.reactive-api.base-url=http://localhost:9999",
            "opentmf.http-clients.reactive-api.basic-auth.username=user",
            "opentmf.http-clients.reactive-api.basic-auth.password=pass")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.containsBean("reactive-apiTokenService")).isTrue();
        });
  }

  @Test
  void nettyClient_bearerMock_registersMockTokenService() {
    contextRunner
        .withPropertyValues(
            "opentmf.client-type=netty",
            "opentmf.http-clients.rsvc.base-url=http://localhost:9999",
            "opentmf.http-clients.rsvc.bearer-auth.use-mock=true",
            "opentmf.http-clients.rsvc.bearer-auth.token-url=http://localhost:9999/token",
            "opentmf.http-clients.rsvc.bearer-auth.form-data.grant_type=client_credentials")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.containsBean("rsvcTokenService")).isTrue();
        });
  }

  @Test
  void jdkClient_bearerAuth_realFlow_registersBearerTokenService() {
    contextRunner
        .withPropertyValues(
            "opentmf.client-type=jdk",
            "opentmf.http-clients.bearer-jdk.base-url=http://localhost:" + mockServer.getLocalPort(),
            "opentmf.http-clients.bearer-jdk.bearer-auth.token-url=" + tokenUrl,
            "opentmf.http-clients.bearer-jdk.bearer-auth.client-id=test-client",
            "opentmf.http-clients.bearer-jdk.bearer-auth.client-secret=test-secret",
            "opentmf.http-clients.bearer-jdk.bearer-auth.form-data.grant_type=client_credentials")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.containsBean("bearer-jdkTokenService")).isTrue();
          var tokenService = (SyncTokenService) context.getBean("bearer-jdkTokenService");
          var token = tokenService.getToken();
          assertThat(token).isEqualTo("test-bearer-token");
        });
  }

  @Test
  void restClient_canPerformGetRequest() {
    mockServer.when(request().withMethod("GET").withPath("/api/item"), Times.once())
        .respond(response().withStatusCode(200).withBody("\"hello from restclient\""));

    contextRunner
        .withPropertyValues(
            "opentmf.client-type=jdk",
            "opentmf.http-clients.rc.base-url=http://localhost:" + mockServer.getLocalPort())
        .run(context -> {
          assertThat(context).hasNotFailed();
          var restClient = (RestClient) context.getBean("rcRestClient");
          var result = restClient.get().uri("/api/item").retrieve().body(String.class);
          assertThat(result).isEqualTo("\"hello from restclient\"");
        });
  }

  @Test
  void restClient_404_throwsNotFoundException() {
    mockServer.when(request().withMethod("GET").withPath("/api/missing"), Times.once())
        .respond(response().withStatusCode(404).withBody("{\"detail\":\"not found\"}"));

    contextRunner
        .withPropertyValues(
            "opentmf.client-type=jdk",
            "opentmf.http-clients.rc404.base-url=http://localhost:" + mockServer.getLocalPort())
        .run(context -> {
          assertThat(context).hasNotFailed();
          var restClient = (RestClient) context.getBean("rc404RestClient");
          assertThatThrownBy(() -> restClient.get().uri("/api/missing")
              .retrieve().body(String.class))
              .isInstanceOf(OpenTmfClientNotFoundException.class);
        });
  }

  @Test
  void restClient_500_throwsClientResponseException() {
    mockServer.when(request().withMethod("GET").withPath("/api/error"), Times.once())
        .respond(response().withStatusCode(500).withBody("{\"message\":\"server error\"}"));

    contextRunner
        .withPropertyValues(
            "opentmf.client-type=jdk",
            "opentmf.http-clients.rc500.base-url=http://localhost:" + mockServer.getLocalPort())
        .run(context -> {
          assertThat(context).hasNotFailed();
          var restClient = (RestClient) context.getBean("rc500RestClient");
          assertThatThrownBy(() -> restClient.get().uri("/api/error")
              .retrieve().body(String.class))
              .isInstanceOf(OpenTmfClientResponseException.class)
              .isNotInstanceOf(OpenTmfClientNotFoundException.class);
        });
  }

  @Test
  void nettyClient_bearerAuth_realFlow_registersBearerTokenService() {
    contextRunner
        .withPropertyValues(
            "opentmf.client-type=netty",
            "opentmf.http-clients.bearer-netty.base-url=http://localhost:" + mockServer.getLocalPort(),
            "opentmf.http-clients.bearer-netty.bearer-auth.token-url=" + tokenUrl,
            "opentmf.http-clients.bearer-netty.bearer-auth.client-id=test-client",
            "opentmf.http-clients.bearer-netty.bearer-auth.client-secret=test-secret",
            "opentmf.http-clients.bearer-netty.bearer-auth.form-data.grant_type=client_credentials")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.containsBean("bearer-nettyTokenService")).isTrue();
          var tokenService = (TokenService) context.getBean("bearer-nettyTokenService");
          StepVerifier.create(tokenService.getToken())
              .expectNext("test-bearer-token")
              .verifyComplete();
        });
  }

  // --- Logbook optional-dependency matrix test (3 client-types x 2 logging x 2 classpath = 12 cases) ---

  @ParameterizedTest(name = "[{index}] client-type={0}, logging-enabled={1}, has-logbook={2}")
  @CsvSource({
      "netty,  true,  true",
      "netty,  true,  false",
      "netty,  false, true",
      "netty,  false, false",
      "jdk,    true,  true",
      "jdk,    true,  false",
      "jdk,    false, true",
      "jdk,    false, false",
      "apache, true,  true",
      "apache, true,  false",
      "apache, false, true",
      "apache, false, false"
  })
  void logbookMatrix(String clientType, boolean loggingEnabled, boolean hasLogbook,
      CapturedOutput output) {

    var runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OpentmfHttpClientsAutoConfiguration.class))
        .withUserConfiguration(WebClientBuilderOnlyConfig.class)
        .withPropertyValues(
            "opentmf.client-type=" + clientType,
            "opentmf.http-clients.svc.base-url=http://localhost:9999",
            "opentmf.http-clients.svc.logging-enabled=" + loggingEnabled);

    if (hasLogbook) {
      runner = runner.withUserConfiguration(LogbookBeanConfig.class);
    } else {
      runner = runner.withClassLoader(new FilteredClassLoader("org.zalando.logbook"));
    }

    runner.run(context -> {
      assertThat(context).hasNotFailed();

      if ("netty".equals(clientType)) {
        assertThat(context.containsBean("svcWebClient")).isTrue();
        assertThat(context.containsBean("svcTokenService")).isTrue();
      } else {
        assertThat(context.containsBean("svcRestTemplate")).isTrue();
        assertThat(context.containsBean("svcRestClient")).isTrue();
        assertThat(context.containsBean("svcTokenService")).isTrue();
      }
    });

    if (loggingEnabled && !hasLogbook) {
      assertThat(output).contains("logging-enabled: true, but no Logbook bean found");
    } else {
      assertThat(output).doesNotContain("logging-enabled: true, but no Logbook bean found");
    }
  }
}
