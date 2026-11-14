package org.opentmf.client.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.model.ClientType;
import org.opentmf.client.starter.registry.HttpClientRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * A bearer-auth client without a token URL is refused by name when it is built, not at its first
 * token call: at context start whether or not a bean validator is on the classpath, and when the
 * dynamic-client registry builds it. A {@code bearer-auth} block is bound as soon as one of its
 * keys is set, so {@code bearer-auth.num-retries} alone makes a bearer client.
 */
class BearerAuthTokenUrlRequiredTest {

  private static final String PREFIX = "opentmf.http-clients.backend.";

  @Configuration(proxyBeanMethods = false)
  static class ReactiveInfraConfig {

    @Bean
    WebClient.Builder webClientBuilder() {
      return WebClient.builder();
    }
  }

  /** Without a bean validator Spring Boot does not validate {@code @Validated} properties. */
  private final ApplicationContextRunner withoutValidator = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(OpentmfHttpClientsAutoConfiguration.class))
      .withUserConfiguration(ReactiveInfraConfig.class)
      .withClassLoader(new FilteredClassLoader("jakarta.validation"));

  private static Throwable rootCause(Throwable failure) {
    var cause = failure;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause;
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache", "netty"})
  void aBearerAuthSubKeyAlone_isRefusedAtStart_byName(String clientType) {
    withoutValidator
        .withPropertyValues("opentmf.client-type=" + clientType,
            PREFIX + "base-url=http://backend", PREFIX + "bearer-auth.num-retries=0")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(rootCause(context.getStartupFailure()))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageStartingWith("Client 'backend' has bearer-auth but no "
                  + "bearer-auth.token-url");
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "netty"})
  void aMockToken_needsNoTokenUrl(String clientType) {
    withoutValidator
        .withPropertyValues("opentmf.client-type=" + clientType,
            PREFIX + "base-url=http://backend", PREFIX + "bearer-auth.use-mock=true")
        .run(context -> assertThat(context).hasNotFailed().hasBean("backendTokenService"));
  }

  @Test
  void theRegistry_refusesItByName_forEitherClientKind() {
    var properties = new ClientProperties();
    properties.setBaseUrl("http://backend");
    var bearer = new BearerAuthConfig();
    bearer.setNumRetries(0);
    properties.setBearerAuth(bearer);

    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OpentmfHttpClientsAutoConfiguration.class))
        .withUserConfiguration(ReactiveInfraConfig.class)
        .withPropertyValues(PREFIX + "base-url=http://backend")
        .run(context -> {
          var registry = context.getBean(HttpClientRegistry.class);
          assertThatThrownBy(() -> registry.getOrCreate("dyn", ClientType.JDK, properties))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageStartingWith("Client 'dyn' has bearer-auth but no bearer-auth.token-url");
          assertThatThrownBy(() -> registry.getOrCreateReactive("dynReactive", properties))
              .isInstanceOf(IllegalStateException.class)
              .hasMessageStartingWith("Client 'dynReactive' has bearer-auth but no "
                  + "bearer-auth.token-url");
          assertThat(registry.names()).isEmpty();
        });
  }
}
