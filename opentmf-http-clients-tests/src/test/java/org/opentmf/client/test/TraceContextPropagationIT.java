package org.opentmf.client.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.opentmf.client.test.util.MockServerUtils.BASE_URL;
import static org.opentmf.client.test.util.MockServerUtils.get;
import static org.opentmf.client.test.util.MockServerUtils.recordedRequests;
import static org.opentmf.client.test.util.MockServerUtils.resetMockServer;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.SenderContext;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * End-to-end verification that when the application publishes an {@link ObservationRegistry},
 * every library-built client — JDK and Apache {@code RestTemplate}, the derived
 * {@code RestClient}, and the reactive {@code WebClient} — carries W3C trace context
 * ({@code traceparent}) on outbound requests. The test registry's handler emulates
 * micrometer-tracing's propagating handler, so this proves the wiring without a tracer
 * dependency; without a registry (covered by the factory unit tests) nothing is sent.
 */
@SpringBootTest
class TraceContextPropagationIT {

  static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  @Autowired private RestTemplate syncRetryAfterRestTemplate;
  @Autowired private RestTemplate apacheRetryRestTemplate;
  @Autowired private RestClient syncRetryAfterRestClient;
  @Autowired private WebClient reactiveRetryAfterWebClient;

  @BeforeEach
  void setUp() {
    resetMockServer();
  }

  @Test
  void jdkRestTemplate_sendsTraceparent() {
    assertTraceparentSent("/api/trace/jdk", path ->
        syncRetryAfterRestTemplate.getForObject(BASE_URL + path, String.class));
  }

  @Test
  void apacheRestTemplate_sendsTraceparent() {
    assertTraceparentSent("/api/trace/apache", path ->
        apacheRetryRestTemplate.getForObject(BASE_URL + path, String.class));
  }

  @Test
  void restClient_inheritsTheRegistryFromItsRestTemplate() {
    assertTraceparentSent("/api/trace/restclient", path ->
        syncRetryAfterRestClient.get().uri(BASE_URL + path).retrieve().body(String.class));
  }

  @Test
  void webClient_sendsTraceparent() {
    assertTraceparentSent("/api/trace/netty", path ->
        reactiveRetryAfterWebClient.get().uri(BASE_URL + path)
            .retrieve().bodyToMono(String.class).block());
  }

  private void assertTraceparentSent(String path, UnaryOperator<String> call) {
    get(path, 1, "ok", HttpStatus.OK);

    assertThat(call.apply(path)).isEqualTo("ok");

    var recorded = recordedRequests(path);
    assertThat(recorded).hasSize(1);
    assertThat(recorded[0].getFirstHeader("traceparent")).isEqualTo(TRACEPARENT);
  }

  @TestConfiguration
  static class ObservationTestConfig {

    @Bean
    ObservationRegistry observationRegistry() {
      var registry = ObservationRegistry.create();
      registry.observationConfig().observationHandler(new HeaderInjectingHandler());
      return registry;
    }
  }

  /**
   * Emulates micrometer-tracing's {@code PropagatingSenderTracingObservationHandler}: on
   * observation start, write trace context onto the outbound carrier.
   */
  static final class HeaderInjectingHandler implements ObservationHandler<SenderContext<Object>> {

    @Override
    public boolean supportsContext(Observation.Context context) {
      return context instanceof SenderContext;
    }

    @Override
    public void onStart(SenderContext<Object> context) {
      Object carrier = context.getCarrier();
      if (carrier != null) {
        context.getSetter().set(carrier, "traceparent", TRACEPARENT);
      }
    }
  }
}
