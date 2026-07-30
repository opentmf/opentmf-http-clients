package org.opentmf.client.common.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.Set;
import lombok.Getter;
import org.jspecify.annotations.Nullable;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.model.ResilienceProperties;

/**
 * Application-wide holder of the resilience4j registries used by the library. One instance per
 * application context; resilience instances are named after the owning client id, so a client's
 * token calls and all of its client shapes (RestTemplate, RestClient, WebClient) share the same
 * CircuitBreaker and Bulkhead.
 *
 * <p>This class must only be instantiated when resilience4j is on the classpath (the starter
 * guards it with a {@code @ConditionalOnClass}).</p>
 */
@Getter
public class ResilienceRegistries {

  private final CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
  private final BulkheadRegistry bulkheadRegistry = BulkheadRegistry.ofDefaults();

  /**
   * Returns the circuit breaker named {@code name}, creating it from the given properties on
   * first use. Later calls with the same name return the existing instance, which is how a
   * client's token WebClient shares the owning client's breaker.
   */
  public CircuitBreaker circuitBreaker(String name, ResilienceProperties properties) {
    return circuitBreakerRegistry.circuitBreaker(name,
        () -> buildCircuitBreakerConfig(properties.getCircuitBreaker()));
  }

  /**
   * Returns the bulkhead named {@code name}, creating it on first use, or {@code null} when the
   * bulkhead is disabled ({@code max-concurrent-calls} not positive).
   */
  public @Nullable Bulkhead bulkhead(String name, ResilienceProperties properties) {
    var props = properties.getBulkhead();
    if (props.getMaxConcurrentCalls() <= 0) {
      return null;
    }
    return bulkheadRegistry.bulkhead(name, () -> BulkheadConfig.custom()
        .maxConcurrentCalls(props.getMaxConcurrentCalls())
        .maxWaitDuration(props.getMaxWaitDuration())
        .build());
  }

  private static CircuitBreakerConfig buildCircuitBreakerConfig(
      ResilienceProperties.CircuitBreakerProperties props) {
    Set<Integer> recordedStatusCodes = Set.copyOf(props.getRecordStatusCodes());
    return CircuitBreakerConfig.custom()
        .failureRateThreshold(props.getFailureRateThreshold())
        .slowCallRateThreshold(props.getSlowCallRateThreshold())
        .slowCallDurationThreshold(props.getSlowCallDurationThreshold())
        .slidingWindowSize(props.getSlidingWindowSize())
        .minimumNumberOfCalls(props.getMinimumNumberOfCalls())
        .waitDurationInOpenState(props.getWaitDurationInOpenState())
        .permittedNumberOfCallsInHalfOpenState(props.getPermittedCallsInHalfOpen())
        .recordException(throwable -> isRecordedFailure(throwable, recordedStatusCodes))
        .build();
  }

  /**
   * Failure predicate shared by the sync and reactive decorations: an HTTP response counts as a
   * failure only when its status is on the record-status-codes list (so 4xx caller bugs never
   * trip the breaker), while everything that is not an HTTP response at all — connect errors,
   * read timeouts, reactive {@code TimeoutException}s — always counts.
   */
  public static boolean isRecordedFailure(Throwable throwable, Set<Integer> recordedStatusCodes) {
    if (throwable instanceof OpenTmfClientResponseException e) {
      return recordedStatusCodes.contains(e.getRawStatusCode());
    }
    return true;
  }
}
