package org.opentmf.client.starter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.opentmf.client.starter.reactive.ReactiveClientRegistrar;
import org.opentmf.client.starter.rest.RestClientRegistrar;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Each registrar is conditioned on ITS OWN module's presence, not only on the Spring class it
 * builds. A servlet-only adopter whose classpath carries spring-webflux through a third party
 * (Spring AI's Ollama module, 2026-09-06) must boot without opentmf-http-clients-reactive — and
 * a reactive-only adopter without opentmf-http-clients-rest. Before the fix the registrar class
 * was loaded for bean introspection and died with NoClassDefFoundError on its module imports.
 */
class RegistrarConditionsIT {

  @Configuration
  static class WebClientOnlyInfra {

    @Bean
    WebClient.Builder webClientBuilder() {
      return WebClient.builder();
    }
  }

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(OpentmfHttpClientsAutoConfiguration.class))
      .withPropertyValues(
          "opentmf.client-type=jdk",
          "opentmf.http-clients.catalog.base-url=http://localhost:9999");

  @Test
  void webfluxOnClasspath_withoutTheReactiveModule_bootsWithRestClientsOnly() {
    runner
        .withUserConfiguration(WebClientOnlyInfra.class)
        .withClassLoader(new FilteredClassLoader("org.opentmf.client.reactive"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(ReactiveClientRegistrar.class);
          assertThat(context).hasBean("catalogRestTemplate");
          assertThat(context).hasBean("catalogRestClient");
        });
  }

  @Test
  void restTemplateOnClasspath_withoutTheRestModule_bootsWithReactiveClientsOnly() {
    runner
        .withUserConfiguration(WebClientOnlyInfra.class)
        .withPropertyValues("opentmf.client-type=netty")
        .withClassLoader(new FilteredClassLoader("org.opentmf.client.rest"))
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).doesNotHaveBean(RestClientRegistrar.class);
          assertThat(context).hasBean("catalogWebClient");
        });
  }
}
