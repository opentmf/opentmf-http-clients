package org.opentmf.client.starter;

import io.micrometer.core.instrument.MeterRegistry;
import org.opentmf.client.common.resilience.ResilienceRegistries;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the application-wide {@link ResilienceRegistries} when resilience4j is on the
 * classpath, and binds its registries to Micrometer when a {@link MeterRegistry} bean and
 * resilience4j-micrometer are present. Absent jars mean no beans and zero behavior change.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = {
    "io.github.resilience4j.circuitbreaker.CircuitBreaker",
    "io.github.resilience4j.bulkhead.Bulkhead"})
public class ResilienceAutoConfiguration {

  @Bean
  public ResilienceRegistries opentmfResilienceRegistries() {
    return new ResilienceRegistries();
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = {
      "io.micrometer.core.instrument.MeterRegistry",
      "io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics"})
  static class ResilienceMetricsConfiguration {

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    public ResilienceMetricsBinder opentmfResilienceMetricsBinder(
        ResilienceRegistries registries, MeterRegistry meterRegistry) {
      return new ResilienceMetricsBinder(registries, meterRegistry);
    }
  }
}
