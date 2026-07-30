package org.opentmf.client.rest.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.opentmf.client.common.exception.OpenTmfClientResilienceException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Decorates every synchronous exchange with Bulkhead → CircuitBreaker → HTTP call. Registered as
 * the outermost interceptor of library-built {@code RestTemplate}s (and inherited by the
 * {@code RestClient} built from them), so the breaker also observes time spent in downstream
 * interceptors.
 *
 * <p>An HTTP response whose status is on the record-status-codes list is recorded as a breaker
 * failure; any other response — including 4xx — counts as success. {@code IOException}s and
 * runtime failures below this interceptor are always recorded. A call rejected by the open
 * breaker or the full bulkhead throws {@link OpenTmfClientResilienceException} without touching
 * the wire.</p>
 */
public class ResilienceClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

  private final String clientId;
  private final CircuitBreaker circuitBreaker;
  private final @Nullable Bulkhead bulkhead;
  private final Set<Integer> recordStatusCodes;

  public ResilienceClientHttpRequestInterceptor(String clientId, CircuitBreaker circuitBreaker,
      @Nullable Bulkhead bulkhead, Set<Integer> recordStatusCodes) {
    this.clientId = clientId;
    this.circuitBreaker = circuitBreaker;
    this.bulkhead = bulkhead;
    this.recordStatusCodes = Set.copyOf(recordStatusCodes);
  }

  @Override
  public ClientHttpResponse intercept(HttpRequest request, byte[] body,
      ClientHttpRequestExecution execution) throws IOException {
    if (bulkhead == null) {
      return callThroughCircuitBreaker(request, body, execution);
    }
    try {
      bulkhead.acquirePermission();
    } catch (BulkheadFullException e) {
      throw new OpenTmfClientResilienceException(clientId,
          "Bulkhead '" + clientId + "' is full — concurrent call limit reached", e);
    }
    try {
      return callThroughCircuitBreaker(request, body, execution);
    } finally {
      bulkhead.onComplete();
    }
  }

  private ClientHttpResponse callThroughCircuitBreaker(HttpRequest request, byte[] body,
      ClientHttpRequestExecution execution) throws IOException {
    try {
      circuitBreaker.acquirePermission();
    } catch (CallNotPermittedException e) {
      throw new OpenTmfClientResilienceException(clientId,
          "Circuit breaker '" + clientId + "' is open — remote is degraded, call rejected", e);
    }
    long start = circuitBreaker.getCurrentTimestamp();
    try {
      ClientHttpResponse response = execution.execute(request, body);
      long duration = circuitBreaker.getCurrentTimestamp() - start;
      var status = response.getStatusCode();
      if (recordStatusCodes.contains(status.value())) {
        circuitBreaker.onError(duration, circuitBreaker.getTimestampUnit(),
            new OpenTmfClientResponseException(status, "HTTP " + status.value()
                + " recorded as circuit breaker failure for client '" + clientId + "'"));
      } else {
        circuitBreaker.onSuccess(duration, circuitBreaker.getTimestampUnit());
      }
      return response;
    } catch (IOException | RuntimeException e) {
      circuitBreaker.onError(circuitBreaker.getCurrentTimestamp() - start,
          circuitBreaker.getTimestampUnit(), e);
      throw e;
    }
  }
}
