package org.opentmf.client.common.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ResiliencePropertiesTest {

  @Test
  void defaults_matchDocumentedValues() {
    var properties = new ResilienceProperties();

    assertThat(properties.isEnabled()).isFalse();

    var circuitBreaker = properties.getCircuitBreaker();
    assertThat(circuitBreaker.getFailureRateThreshold()).isEqualTo(50);
    assertThat(circuitBreaker.getSlowCallRateThreshold()).isEqualTo(100);
    assertThat(circuitBreaker.getSlowCallDurationThreshold()).isEqualTo(Duration.ofSeconds(5));
    assertThat(circuitBreaker.getSlidingWindowSize()).isEqualTo(50);
    assertThat(circuitBreaker.getMinimumNumberOfCalls()).isEqualTo(20);
    assertThat(circuitBreaker.getWaitDurationInOpenState()).isEqualTo(Duration.ofSeconds(30));
    assertThat(circuitBreaker.getPermittedCallsInHalfOpen()).isEqualTo(5);
    assertThat(circuitBreaker.getRecordStatusCodes()).containsExactly(500, 502, 503, 504);

    assertThat(properties.getBulkhead().getMaxConcurrentCalls()).isZero();
    assertThat(properties.getBulkhead().getMaxWaitDuration()).isEqualTo(Duration.ZERO);

    assertThat(properties.getTimeLimiter().getTimeoutDuration()).isNull();
  }

  @Test
  void clientProperties_resilienceNeverNull() {
    assertThat(new ClientProperties().getResilience()).isNotNull();
    assertThat(new ClientProperties().getResilience().isEnabled()).isFalse();
  }
}
