package org.opentmf.client.starter;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.resilience.ResilienceRegistries;
import org.opentmf.client.rest.resilience.ResilienceClientHttpRequestInterceptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

class ResilienceAutoConfigurationIT {

  @Configuration(proxyBeanMethods = false)
  static class ReactiveInfraConfig {

    @Bean
    WebClient.Builder webClientBuilder() {
      return WebClient.builder();
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class MeterRegistryConfig {

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(OpentmfHttpClientsAutoConfiguration.class))
      .withUserConfiguration(ReactiveInfraConfig.class);

  @Test
  void enabledResilience_addsOutermostInterceptor_andRegistersInstances() {
    contextRunner
        .withPropertyValues(
            "opentmf.client-type=jdk",
            "opentmf.http-clients.onedms.base-url=http://localhost:9999",
            "opentmf.http-clients.onedms.resilience.enabled=true",
            "opentmf.http-clients.onedms.resilience.bulkhead.max-concurrent-calls=10")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var restTemplate = (RestTemplate) context.getBean("onedmsRestTemplate");
          assertThat(restTemplate.getInterceptors().get(0))
              .isInstanceOf(ResilienceClientHttpRequestInterceptor.class);

          var registries = context.getBean(ResilienceRegistries.class);
          assertThat(registries.getCircuitBreakerRegistry().getAllCircuitBreakers())
              .extracting(CircuitBreaker::getName)
              .containsExactly("onedms");
          assertThat(registries.getBulkheadRegistry().getAllBulkheads()).hasSize(1);
        });
  }

  @Test
  void disabledResilience_leavesClientUndecorated() {
    contextRunner
        .withPropertyValues(
            "opentmf.client-type=jdk",
            "opentmf.http-clients.plain.base-url=http://localhost:9999")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var restTemplate = (RestTemplate) context.getBean("plainRestTemplate");
          assertThat(restTemplate.getInterceptors())
              .noneMatch(ResilienceClientHttpRequestInterceptor.class::isInstance);
          assertThat(context.getBean(ResilienceRegistries.class)
              .getCircuitBreakerRegistry().getAllCircuitBreakers()).isEmpty();
        });
  }

  @Test
  void reactiveBearerClient_tokenWebClient_sharesOwnersCircuitBreaker() {
    contextRunner
        .withPropertyValues(
            "opentmf.client-type=netty",
            "opentmf.http-clients.asgw.base-url=http://localhost:9999",
            "opentmf.http-clients.asgw.resilience.enabled=true",
            "opentmf.http-clients.asgw.bearer-auth.token-url=http://localhost:9999/token",
            "opentmf.http-clients.asgw.bearer-auth.form-data.grant_type=client_credentials")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.containsBean("asgwWebClient")).isTrue();
          var registries = context.getBean(ResilienceRegistries.class);
          assertThat(registries.getCircuitBreakerRegistry().getAllCircuitBreakers())
              .extracting(CircuitBreaker::getName)
              .containsExactly("asgw");
        });
  }

  @Test
  void meterRegistryPresent_bindsCircuitBreakerMetrics() {
    contextRunner
        .withUserConfiguration(MeterRegistryConfig.class)
        .withPropertyValues(
            "opentmf.client-type=jdk",
            "opentmf.http-clients.observed.base-url=http://localhost:9999",
            "opentmf.http-clients.observed.resilience.enabled=true")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(ResilienceMetricsBinder.class);
          var meterRegistry = context.getBean(MeterRegistry.class);
          assertThat(meterRegistry.find("resilience4j.circuitbreaker.state")
              .tag("name", "observed").meters()).isNotEmpty();
        });
  }

  @Test
  void resilience4jAbsent_enabledClient_failsWithGuidance() {
    contextRunner
        .withClassLoader(new FilteredClassLoader(CircuitBreaker.class))
        .withPropertyValues(
            "opentmf.client-type=jdk",
            "opentmf.http-clients.orphan.base-url=http://localhost:9999",
            "opentmf.http-clients.orphan.resilience.enabled=true")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasRootCauseMessage(rootCauseMessage());
        });
  }

  private static String rootCauseMessage() {
    return "Client 'orphan' has resilience.enabled: true, but resilience4j is not on the "
        + "classpath. Add io.github.resilience4j:resilience4j-circuitbreaker and "
        + "resilience4j-bulkhead, or disable resilience.";
  }

  @Test
  void resilience4jAbsent_defaultConfig_startsCleanly() {
    contextRunner
        .withClassLoader(new FilteredClassLoader(CircuitBreaker.class))
        .withPropertyValues(
            "opentmf.client-type=jdk",
            "opentmf.http-clients.legacy.base-url=http://localhost:9999")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.containsBean("legacyRestTemplate")).isTrue();
          assertThat(context).doesNotHaveBean(ResilienceRegistries.class);
        });
  }
}
