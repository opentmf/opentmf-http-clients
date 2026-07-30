package org.opentmf.client.starter;

import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.opentmf.client.common.resilience.ResilienceRegistries;

/**
 * Binds the library's CircuitBreaker and Bulkhead registries to Micrometer. Instances created
 * after binding (clients are registered lazily by id) are picked up automatically through the
 * resilience4j registry event publishers, so metrics like
 * {@code resilience4j.circuitbreaker.state{name="<clientId>"}} appear on the standard scrape
 * without any consumer wiring.
 */
public class ResilienceMetricsBinder {

  public ResilienceMetricsBinder(ResilienceRegistries registries, MeterRegistry meterRegistry) {
    TaggedCircuitBreakerMetrics
        .ofCircuitBreakerRegistry(registries.getCircuitBreakerRegistry())
        .bindTo(meterRegistry);
    TaggedBulkheadMetrics
        .ofBulkheadRegistry(registries.getBulkheadRegistry())
        .bindTo(meterRegistry);
  }
}
