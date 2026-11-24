package org.opentmf.client.starter.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockConstruction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
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
import org.springframework.core.SpringVersion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.converter.xml.Jaxb2RootElementHttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * A client derived from the library's {@code RestClient} — {@code mutate()...build()}, as a
 * consumer does per call to add a credential — does not detect Spring's default message converters
 * again. Detection constructs the classpath's converters (the JAXB one scans jars) and costs tens
 * of milliseconds per derived client. The derived client carries the library client's converters,
 * the same instances in the same order, and their layout is the one the 2.5 clients had: Spring's
 * default client converters, then Spring's one trailing form or multipart converter. Both hold on
 * every Spring Framework line the library runs on: 7.0 applies a converter configurer instead of
 * the defaults, 7.1 applies it on top of them.
 */
class RestClientConvertersIT {

  private static final int DERIVED = 20;

  private static final String CONVERTER = "org.springframework.http.converter.";

  /** Spring Framework 7.1's client converters on this classpath, then its trailing multipart one. */
  private static final List<String> SEVEN_ONE_LAYOUT = Stream.of("ByteArrayHttpMessageConverter",
      "StringHttpMessageConverter", "FormHttpMessageConverter", "ResourceHttpMessageConverter",
      "multipart.MultipartHttpMessageConverter", "json.JacksonJsonHttpMessageConverter",
      "yaml.JacksonYamlHttpMessageConverter", "xml.Jaxb2RootElementHttpMessageConverter",
      "multipart.MultipartHttpMessageConverter").map(CONVERTER::concat).toList();

  /** Spring Framework 7.0's client converters on this classpath, then its trailing form one. */
  private static final List<String> SEVEN_ZERO_LAYOUT = Stream.of("ByteArrayHttpMessageConverter",
      "StringHttpMessageConverter", "ResourceHttpMessageConverter",
      "support.AllEncompassingFormHttpMessageConverter", "json.JacksonJsonHttpMessageConverter",
      "yaml.JacksonYamlHttpMessageConverter", "xml.Jaxb2RootElementHttpMessageConverter",
      "support.AllEncompassingFormHttpMessageConverter").map(CONVERTER::concat).toList();

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
  void derivingTheBeanClient_constructsNoConverter_andKeepsTheTwoFiveLayout(String clientType) {
    contextRunner
        .withPropertyValues("opentmf.client-type=" + clientType,
            "opentmf.http-clients.store.base-url=http://localhost:9999")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertDerivingIsCheap((RestClient) context.getBean("storeRestClient"));
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache"})
  void derivingADynamicClient_constructsNoConverter_andKeepsTheTwoFiveLayout(
      String clientType) {
    contextRunner
        .withPropertyValues("opentmf.http-clients.unused.base-url=http://localhost:9999")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var properties = new ClientProperties();
          properties.setBaseUrl("http://localhost:9999");
          var managed = context.getBean(HttpClientRegistry.class)
              .getOrCreateRestClient("dynamic", ClientType.valueOf(clientType.toUpperCase()),
                  properties);
          assertDerivingIsCheap(managed.restClient());
        });
  }

  /**
   * For each derivation: deriving {@link #DERIVED} clients constructs no JSON and no JAXB converter
   * (Spring's detection constructs both on every build that registers the defaults), and no string
   * converter beyond the one Spring 7.0's trailing form converter constructs per build (7.1's
   * multipart converter constructs none). Every derived client carries the library client's
   * converters, the same instances in the same order, and Spring's trailing one of its own.
   */
  private static void assertDerivingIsCheap(RestClient restClient) {
    for (var derivation : DERIVATIONS) {
      var derived = derive(restClient, derivation);

      assertThat(derived.json()).as("JSON converters constructed while deriving %d clients",
          DERIVED).isZero();
      assertThat(derived.jaxb()).as("JAXB converters constructed while deriving %d clients",
          DERIVED).isZero();
      assertThat(derived.string()).as("string converters constructed while deriving %d clients",
          DERIVED).isEqualTo(springSevenZero() ? DERIVED : 0);
      derived.clients().forEach(client -> assertTheSameConverters(client, restClient));
    }
    assertTheTwoFiveLayout(restClient);
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
   * The client's converters are the layout the 2.5 clients had on the same Spring version:
   * Spring's default client converters on this classpath, once each and in their order, then the
   * trailing form converter (7.0) or multipart converter (7.1).
   */
  private static void assertTheTwoFiveLayout(RestClient restClient) {
    assertThat(converters(restClient)).extracting(converter -> converter.getClass().getName())
        .containsExactlyElementsOf(springSevenZero() ? SEVEN_ZERO_LAYOUT : SEVEN_ONE_LAYOUT);
  }

  /** The same instances in the same order, but for Spring's trailing converter, built per build. */
  private static void assertTheSameConverters(RestClient derived, RestClient restClient) {
    var expected = converters(restClient);
    var actual = converters(derived);
    assertThat(actual).hasSameSizeAs(expected);
    for (int i = 0; i < expected.size() - 1; i++) {
      assertThat(actual.get(i)).isSameAs(expected.get(i));
    }
    assertThat(actual.get(actual.size() - 1)).hasSameClassAs(expected.get(expected.size() - 1));
  }

  private static boolean springSevenZero() {
    return SpringVersion.getVersion().startsWith("7.0.");
  }

  @SuppressWarnings("unchecked")
  private static List<HttpMessageConverter<?>> converters(RestClient restClient) {
    return (List<HttpMessageConverter<?>>) ReflectionTestUtils.getField(restClient,
        "messageConverters");
  }
}
