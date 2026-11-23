package org.opentmf.client.starter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockConstruction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.model.ClientType;
import org.opentmf.client.starter.OpentmfHttpClientsAutoConfiguration;
import org.opentmf.client.starter.registry.HttpClientRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.converter.xml.Jaxb2RootElementHttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * A client derived from the library's {@code RestClient} — {@code mutate()...build()}, as a
 * consumer does per call to add a credential — does not detect Spring's default message converters
 * again. Detection constructs the classpath's converters (the JAXB one scans jars) and cost tens of
 * milliseconds per derived client at 2.6.0-…-1 and -2. The derived client's converters are the ones
 * a 2.5.2 client had, which was built with {@code RestClient.builder(restTemplate)}: the
 * {@code RestTemplate}'s, the same instances in the same order, then Spring's one trailing form or
 * multipart converter. Both hold on every Spring Framework line the library runs on: 7.0 applies a
 * converter configurer instead of the defaults, 7.1 applies it on top of them.
 */
class RestClientConvertersIT {

  private static final int DERIVED = 20;

  /** The two ways consumers derive a client per call. */
  private static final List<UnaryOperator<RestClient.Builder>> DERIVATIONS = List.of(
      builder -> builder.requestInterceptor(
          (request, body, execution) -> execution.execute(request, body)),
      builder -> builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer token"));

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
  void derivingTheBeanClient_constructsNoConverter_andKeepsTheTwoFiveTwoLayout(String clientType) {
    contextRunner
        .withPropertyValues("opentmf.client-type=" + clientType,
            "opentmf.http-clients.store.base-url=http://localhost:9999")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var bean = (RestClient) context.getBean("storeRestClient");
          var restTemplate = (RestTemplate) context.getBean("storeRestTemplate");
          assertDerivingIsCheap(bean, restTemplate);
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache"})
  void derivingADynamicClient_constructsNoConverter_andKeepsTheTwoFiveTwoLayout(
      String clientType) {
    contextRunner
        .withPropertyValues("opentmf.http-clients.unused.base-url=http://localhost:9999")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var properties = new ClientProperties();
          properties.setBaseUrl("http://localhost:9999");
          var managed = context.getBean(HttpClientRegistry.class)
              .getOrCreate("dynamic", ClientType.valueOf(clientType.toUpperCase()), properties);
          assertDerivingIsCheap(managed.restClient(), managed.restTemplate());
        });
  }

  /**
   * For each derivation: deriving {@link #DERIVED} clients constructs no JSON and no JAXB converter
   * (Spring's detection constructs both on every build that registers the defaults), and no more
   * string converters than deriving a client built as 2.5.2 built it, on the same Spring version
   * (7.0's trailing form converter constructs one per build, 7.1's multipart converter none).
   */
  private static void assertDerivingIsCheap(RestClient restClient, RestTemplate restTemplate) {
    var twoFiveTwo = RestClient.builder(restTemplate).build();
    for (var derivation : DERIVATIONS) {
      var baseline = derive(twoFiveTwo, derivation);
      var derived = derive(restClient, derivation);

      assertThat(derived.json()).as("JSON converters constructed while deriving %d clients",
          DERIVED).isZero();
      assertThat(derived.jaxb()).as("JAXB converters constructed while deriving %d clients",
          DERIVED).isZero();
      assertThat(derived.string()).as("string converters constructed while deriving %d clients",
          DERIVED).isEqualTo(baseline.string());
      derived.clients().forEach(client -> assertTheTwoFiveTwoLayout(client, restTemplate));
    }
    assertTheTwoFiveTwoLayout(restClient, restTemplate);
  }

  private record Derived(List<RestClient> clients, int json, int jaxb, int string) {
  }

  private static Derived derive(RestClient restClient,
      UnaryOperator<RestClient.Builder> derivation) {
    var clients = new ArrayList<RestClient>();
    try (MockedConstruction<JacksonJsonHttpMessageConverter> json =
            mockConstruction(JacksonJsonHttpMessageConverter.class);
        MockedConstruction<Jaxb2RootElementHttpMessageConverter> jaxb =
            mockConstruction(Jaxb2RootElementHttpMessageConverter.class);
        MockedConstruction<StringHttpMessageConverter> string =
            mockConstruction(StringHttpMessageConverter.class)) {
      for (int i = 0; i < DERIVED; i++) {
        clients.add(derivation.apply(restClient.mutate()).build());
      }
      return new Derived(clients, json.constructed().size(), jaxb.constructed().size(),
          string.constructed().size());
    }
  }

  /**
   * The client's converters equal those of a client built as 2.5.2 built it, with
   * {@code RestClient.builder(restTemplate)} on the same Spring version: the same size and classes,
   * once each, and the {@code RestTemplate}'s converters as the same instances.
   */
  private static void assertTheTwoFiveTwoLayout(RestClient restClient, RestTemplate restTemplate) {
    var converters = converters(restClient);
    var twoFiveTwo = converters(RestClient.builder(restTemplate).build());
    assertThat(converters).hasSameSizeAs(twoFiveTwo);
    assertThat(converters).extracting(Object::getClass)
        .containsExactlyElementsOf(twoFiveTwo.stream().<Class<?>>map(Object::getClass).toList());
    var templateConverters = restTemplate.getMessageConverters();
    for (int i = 0; i < templateConverters.size(); i++) {
      assertThat(converters.get(i)).isSameAs(templateConverters.get(i));
    }
  }

  @SuppressWarnings("unchecked")
  private static List<HttpMessageConverter<?>> converters(RestClient restClient) {
    return (List<HttpMessageConverter<?>>) ReflectionTestUtils.getField(restClient,
        "messageConverters");
  }
}
