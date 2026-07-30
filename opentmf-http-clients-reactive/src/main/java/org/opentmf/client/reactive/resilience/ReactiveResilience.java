package org.opentmf.client.reactive.resilience;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.jspecify.annotations.Nullable;
import org.opentmf.client.common.exception.OpenTmfClientResilienceException;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.resilience.ResilienceRegistries;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Applies the resilience4j decoration to a {@code WebClient}: Bulkhead → CircuitBreaker →
 * time limit → exchange. The filter is inserted OUTERMOST (before the library's error-wrapping
 * filter), so the breaker observes the {@code OpenTmfClientResponseException}s produced by the
 * inner filter and records only the configured status codes as failures; connect errors and
 * {@code TimeoutException}s are always recorded.
 */
public final class ReactiveResilience {

  private ReactiveResilience() {
  }

  /**
   * Returns the given client decorated per {@code properties.resilience}, or the client
   * unchanged when resilience is disabled. The {@code resilienceName} keys the shared
   * CircuitBreaker/Bulkhead instances — pass the OWNING client id, also for the client's token
   * WebClient, so a broken token endpoint opens the same circuit.
   *
   * @throws IllegalStateException when resilience is enabled but resilience4j is absent
   */
  public static WebClient decorate(WebClient webClient, String resilienceName,
      ClientProperties properties, @Nullable ResilienceRegistries registries) {
    var resilience = properties.getResilience();
    if (!resilience.isEnabled()) {
      return webClient;
    }
    if (registries == null) {
      throw new IllegalStateException("Client '" + resilienceName + "' has resilience.enabled: "
          + "true, but resilience4j is not on the classpath. Add io.github.resilience4j:"
          + "resilience4j-circuitbreaker, resilience4j-bulkhead and resilience4j-reactor, "
          + "or disable resilience.");
    }
    var circuitBreaker = registries.circuitBreaker(resilienceName, resilience);
    var bulkhead = registries.bulkhead(resilienceName, resilience);
    var timeout = resilience.getTimeLimiter().getTimeoutDuration();

    ExchangeFilterFunction filter = (request, next) -> {
      // Mono.defer keeps the downstream exchange unassembled until the breaker and bulkhead
      // have granted permission — a rejected call must never touch the exchange function.
      Mono<ClientResponse> exchange = Mono.defer(() -> {
        var call = next.exchange(request);
        return timeout != null ? call.timeout(timeout) : call;
      });
      exchange = exchange.transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
      if (bulkhead != null) {
        exchange = exchange.transformDeferred(BulkheadOperator.of(bulkhead));
      }
      return exchange.onErrorMap(throwable -> mapRejection(resilienceName, throwable));
    };
    return webClient.mutate().filters(filters -> filters.add(0, filter)).build();
  }

  private static Throwable mapRejection(String resilienceName, Throwable throwable) {
    if (throwable instanceof CallNotPermittedException) {
      return new OpenTmfClientResilienceException(resilienceName,
          "Circuit breaker '" + resilienceName + "' is open — remote is degraded, call rejected",
          throwable);
    }
    if (throwable instanceof BulkheadFullException) {
      return new OpenTmfClientResilienceException(resilienceName,
          "Bulkhead '" + resilienceName + "' is full — concurrent call limit reached", throwable);
    }
    return throwable;
  }
}
