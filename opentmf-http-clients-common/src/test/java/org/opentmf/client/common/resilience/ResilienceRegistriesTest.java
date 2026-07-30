package org.opentmf.client.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.model.ResilienceProperties;
import org.springframework.http.HttpStatus;

class ResilienceRegistriesTest {

  private final ResilienceRegistries registries = new ResilienceRegistries();

  @Test
  void circuitBreaker_isBuiltFromProperties() {
    var properties = new ResilienceProperties();
    var cbProps = properties.getCircuitBreaker();
    cbProps.setFailureRateThreshold(33);
    cbProps.setSlowCallRateThreshold(66);
    cbProps.setSlowCallDurationThreshold(Duration.ofSeconds(2));
    cbProps.setSlidingWindowSize(10);
    cbProps.setMinimumNumberOfCalls(4);
    cbProps.setWaitDurationInOpenState(Duration.ofSeconds(7));
    cbProps.setPermittedCallsInHalfOpen(2);

    var circuitBreaker = registries.circuitBreaker("configured", properties);
    var config = circuitBreaker.getCircuitBreakerConfig();

    assertThat(config.getFailureRateThreshold()).isEqualTo(33);
    assertThat(config.getSlowCallRateThreshold()).isEqualTo(66);
    assertThat(config.getSlowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(2));
    assertThat(config.getSlidingWindowSize()).isEqualTo(10);
    assertThat(config.getMinimumNumberOfCalls()).isEqualTo(4);
    assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(2);
  }

  @Test
  void circuitBreaker_sameName_returnsSameInstance() {
    var properties = new ResilienceProperties();
    var first = registries.circuitBreaker("shared", properties);
    var second = registries.circuitBreaker("shared", properties);
    assertThat(second).isSameAs(first);
  }

  @Test
  void bulkhead_disabledByDefault_returnsNull() {
    assertThat(registries.bulkhead("none", new ResilienceProperties())).isNull();
  }

  @Test
  void bulkhead_positiveLimit_isBuiltAndShared() {
    var properties = new ResilienceProperties();
    properties.getBulkhead().setMaxConcurrentCalls(5);
    properties.getBulkhead().setMaxWaitDuration(Duration.ofMillis(50));

    var bulkhead = registries.bulkhead("limited", properties);

    assertThat(bulkhead).isNotNull();
    assertThat(bulkhead.getBulkheadConfig().getMaxConcurrentCalls()).isEqualTo(5);
    assertThat(bulkhead.getBulkheadConfig().getMaxWaitDuration())
        .isEqualTo(Duration.ofMillis(50));
    assertThat(registries.bulkhead("limited", properties)).isSameAs(bulkhead);
  }

  @Test
  void recordExceptionPredicate_followsRecordStatusCodes() {
    var properties = new ResilienceProperties();
    properties.getCircuitBreaker().setRecordStatusCodes(List.of(500, 503));
    var predicate = registries.circuitBreaker("predicate", properties)
        .getCircuitBreakerConfig().getRecordExceptionPredicate();

    assertThat(predicate.test(
        new OpenTmfClientResponseException(HttpStatus.SERVICE_UNAVAILABLE))).isTrue();
    assertThat(predicate.test(
        new OpenTmfClientResponseException(HttpStatus.BAD_REQUEST))).isFalse();
    assertThat(predicate.test(
        new OpenTmfClientNotFoundException(HttpStatus.NOT_FOUND, "gone"))).isFalse();
    assertThat(predicate.test(new IOException("connection reset"))).isTrue();
    assertThat(predicate.test(new TimeoutException("too slow"))).isTrue();
  }

  @Test
  void isRecordedFailure_checksStatusOnlyForResponseExceptions() {
    Set<Integer> recorded = Set.of(502);
    assertThat(ResilienceRegistries.isRecordedFailure(
        new OpenTmfClientResponseException(HttpStatus.BAD_GATEWAY), recorded)).isTrue();
    assertThat(ResilienceRegistries.isRecordedFailure(
        new OpenTmfClientResponseException(HttpStatus.INTERNAL_SERVER_ERROR), recorded)).isFalse();
    assertThat(ResilienceRegistries.isRecordedFailure(
        new IllegalStateException("boom"), recorded)).isTrue();
  }
}
