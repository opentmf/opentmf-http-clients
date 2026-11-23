package org.opentmf.client.starter.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.model.ClientType;
import org.opentmf.client.starter.OpentmfHttpClientsAutoConfiguration;
import org.opentmf.client.starter.registry.HttpClientRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.converter.support.AllEncompassingFormHttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * A client derived from the library's {@code RestClient} — {@code mutate()...build()}, as a
 * consumer does per call to add a credential — reuses the client's message converters: they are
 * assembled once per client, not on every build. Assembling Spring's default set detects the
 * classpath (the JAXB converter scans jars) and cost about 15 ms per derived client at 2.6.0-…-1.
 * The converters are the ones a 2.5.2 client had: the {@code RestTemplate}'s, the same instances in
 * the same order, then one form converter.
 */
class RestClientConvertersIT {

  private static final int DERIVED = 20;

  @Configuration(proxyBeanMethods = false)
  static class ReactiveInfraConfig {

    @Bean
    WebClient.Builder webClientBuilder() {
      return WebClient.builder();
    }
  }

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(OpentmfHttpClientsAutoConfiguration.class))
      .withUserConfiguration(ReactiveInfraConfig.class);

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache"})
  void derivedClients_reuseTheBeansConverters_assembledOnce(String clientType) {
    contextRunner
        .withPropertyValues("opentmf.client-type=" + clientType,
            "opentmf.http-clients.store.base-url=http://localhost:9999")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var bean = (RestClient) context.getBean("storeRestClient");
          var clients = new ArrayList<RestClient>();
          clients.add(bean);
          for (int i = 0; i < DERIVED; i++) {
            clients.add(bean.mutate()
                .requestInterceptor((request, body, execution) -> execution.execute(request, body))
                .build());
          }

          var restTemplate = (RestTemplate) context.getBean("storeRestTemplate");
          assertThat(distinct(clients, JacksonJsonHttpMessageConverter.class, restTemplate))
              .as("JSON converter instances across the bean, %d derived clients and the template",
                  DERIVED)
              .hasSize(1);
          clients.forEach(client -> assertTheTwoFiveTwoLayout(client, restTemplate));
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache"})
  void dynamicClients_reuseTheirConverters_assembledOnce(String clientType) {
    contextRunner
        .withPropertyValues("opentmf.http-clients.unused.base-url=http://localhost:9999")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var properties = new ClientProperties();
          properties.setBaseUrl("http://localhost:9999");
          var managed = context.getBean(HttpClientRegistry.class)
              .getOrCreate("dynamic", ClientType.valueOf(clientType.toUpperCase()), properties);
          var clients = new ArrayList<RestClient>();
          clients.add(managed.restClient());
          for (int i = 0; i < DERIVED; i++) {
            clients.add(managed.restClient().mutate().build());
          }

          assertThat(distinct(clients, JacksonJsonHttpMessageConverter.class,
              managed.restTemplate())).hasSize(1);
          clients.forEach(client -> assertTheTwoFiveTwoLayout(client, managed.restTemplate()));
        });
  }

  /** As a 2.5.2 client built with {@code RestClient.builder(restTemplate)}. */
  private static void assertTheTwoFiveTwoLayout(RestClient restClient, RestTemplate restTemplate) {
    var converters = converters(restClient);
    var templateConverters = restTemplate.getMessageConverters();
    assertThat(converters).hasSize(templateConverters.size() + 1);
    for (int i = 0; i < templateConverters.size(); i++) {
      assertThat(converters.get(i)).isSameAs(templateConverters.get(i));
    }
    assertThat(converters.get(templateConverters.size()))
        .isInstanceOf(AllEncompassingFormHttpMessageConverter.class);
  }

  private static Set<Object> distinct(List<RestClient> clients, Class<?> converterType,
      RestTemplate restTemplate) {
    Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (var client : clients) {
      converters(client).stream().filter(converterType::isInstance).forEach(seen::add);
    }
    restTemplate.getMessageConverters().stream().filter(converterType::isInstance)
        .forEach(seen::add);
    return seen;
  }

  @SuppressWarnings("unchecked")
  private static List<HttpMessageConverter<?>> converters(RestClient restClient) {
    return (List<HttpMessageConverter<?>>) ReflectionTestUtils.getField(restClient,
        "messageConverters");
  }
}
