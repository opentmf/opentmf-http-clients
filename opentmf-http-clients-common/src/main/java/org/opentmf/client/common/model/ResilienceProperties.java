package org.opentmf.client.common.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Duration;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.validation.annotation.Validated;

/**
 * Optional resilience4j decoration of a client, bound from
 * {@code opentmf.http-clients.<clientId>.resilience.*}. Disabled by default; when enabled, every
 * client shape of the id (RestTemplate, RestClient or WebClient, plus its bearer-token calls)
 * runs inside the same named CircuitBreaker and, when configured, Bulkhead. Requires the
 * resilience4j-circuitbreaker and resilience4j-bulkhead jars (plus resilience4j-reactor for
 * netty clients) on the classpath.
 */
@Validated
@Getter
@Setter
public class ResilienceProperties {

  /**
   * Master switch for resilience decoration of this client. Default false — absent property or
   * absent resilience4j jars mean the client behaves exactly as before.
   */
  private boolean enabled = false;

  /**
   * Circuit-breaker settings; the instance is named after the client id.
   */
  @Valid
  private CircuitBreakerProperties circuitBreaker = new CircuitBreakerProperties();

  /**
   * Bulkhead (concurrency limit) settings. Disabled unless max-concurrent-calls is positive.
   */
  @Valid
  private BulkheadProperties bulkhead = new BulkheadProperties();

  /**
   * Per-call time budget, applied to reactive (netty) clients only; synchronous clients bound
   * call time via request-timeout and response-timeout instead.
   */
  @Valid
  private TimeLimiterProperties timeLimiter = new TimeLimiterProperties();

  /**
   * Circuit-breaker tuning, mapping 1:1 onto resilience4j's CircuitBreakerConfig.
   */
  @Validated
  @Getter
  @Setter
  public static class CircuitBreakerProperties {

    /**
     * Failure-rate percentage at or above which the circuit opens once the sliding window holds
     * minimum-number-of-calls results. Default 50.
     */
    @DecimalMin("1")
    @DecimalMax("100")
    private float failureRateThreshold = 50;

    /**
     * Slow-call-rate percentage at or above which the circuit opens. Default 100 (slow calls
     * alone never open the circuit).
     */
    @DecimalMin("1")
    @DecimalMax("100")
    private float slowCallRateThreshold = 100;

    /**
     * Calls taking longer than this count as slow. Default 5s.
     */
    private Duration slowCallDurationThreshold = Duration.ofSeconds(5);

    /**
     * Number of calls in the count-based sliding window. Default 50.
     */
    @Positive
    private int slidingWindowSize = 50;

    /**
     * Minimum number of recorded calls before failure/slow rates are evaluated. Default 20.
     */
    @Positive
    private int minimumNumberOfCalls = 20;

    /**
     * How long the circuit stays open before permitting half-open trial calls. Default 30s.
     */
    private Duration waitDurationInOpenState = Duration.ofSeconds(30);

    /**
     * Number of trial calls permitted in the half-open state. Default 5.
     */
    @Positive
    private int permittedCallsInHalfOpen = 5;

    /**
     * HTTP status codes recorded as circuit-breaker failures. Client-side 4xx responses should
     * stay off this list — they indicate caller bugs, not a degraded remote. Connection errors
     * and timeouts are always recorded. Default [500, 502, 503, 504].
     */
    private List<Integer> recordStatusCodes = List.of(500, 502, 503, 504);
  }

  /**
   * Bulkhead (semaphore) tuning, mapping onto resilience4j's BulkheadConfig.
   */
  @Validated
  @Getter
  @Setter
  public static class BulkheadProperties {

    /**
     * Maximum concurrent calls through this client. 0 (the default) disables the bulkhead.
     */
    @PositiveOrZero
    private int maxConcurrentCalls = 0;

    /**
     * How long a call waits for a bulkhead permit before failing. Default 0s (fail immediately).
     */
    private Duration maxWaitDuration = Duration.ZERO;
  }

  /**
   * Per-call time budget for reactive clients.
   */
  @Validated
  @Getter
  @Setter
  public static class TimeLimiterProperties {

    /**
     * Maximum duration of a single call (netty clients only). Unset (the default) disables the
     * time limiter. A timed-out call is recorded as a circuit-breaker failure.
     */
    private Duration timeoutDuration;
  }
}
