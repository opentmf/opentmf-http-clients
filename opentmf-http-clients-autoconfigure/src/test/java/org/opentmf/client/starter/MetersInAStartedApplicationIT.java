package org.opentmf.client.starter;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The library's meters in an application started the way a service starts: auto-configuration
 * only, the {@link MeterRegistry} made by Boot's metrics auto-configuration, none handed in by
 * the application. The other meter ITs hand the registry in as user configuration, which is
 * processed before every auto-configuration and so hid the ordering this pins.
 */
class MetersInAStartedApplicationIT {

  private static final List<String> POOL_GAUGES = List.of("opentmf.client.pool.leased",
      "opentmf.client.pool.available", "opentmf.client.pool.pending", "opentmf.client.pool.max");

  /** A service: auto-configuration, and the {@code WebClient.Builder} a reactive service has. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class Service {

    @Bean
    WebClient.Builder webClientBuilder() {
      return WebClient.builder();
    }
  }

  @Test
  void aStartedApplication_hasTheFiveMeters_andTheResilienceBinder() {
    try (var context = new SpringApplicationBuilder(Service.class)
        .web(WebApplicationType.NONE)
        .properties(
            "opentmf.http-clients.backend.client-type=apache",
            "opentmf.http-clients.backend.base-url=http://localhost:9999",
            "opentmf.http-clients.backend.bearer-auth.token-url=http://localhost:9999/token",
            "opentmf.http-clients.backend.bearer-auth.client-id=client",
            "opentmf.http-clients.backend.bearer-auth.client-secret=secret",
            "opentmf.http-clients.backend.bearer-auth.form-data.grant_type=client_credentials")
        .run()) {
      var registry = context.getBean(MeterRegistry.class);

      assertThat(registry.find(TokenFetchMeters.METER_NAME).tag("client", "backend").counters())
          .as("opentmf.client.token.fetch{client=backend}, one per outcome")
          .hasSize(3);
      for (var gauge : POOL_GAUGES) {
        assertThat(registry.find(gauge).tag("client", "backend").gauge()).as(gauge).isNotNull();
      }
      assertThat(context.getBeanProvider(ResilienceMetricsBinder.class).getIfAvailable())
          .as("the resilience4j meters binder")
          .isNotNull();
    }
  }
}
