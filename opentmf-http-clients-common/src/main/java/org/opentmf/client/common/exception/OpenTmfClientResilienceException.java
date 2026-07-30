package org.opentmf.client.common.exception;

import java.io.Serial;
import lombok.Getter;

/**
 * Thrown when a call is rejected by the client's resilience decoration before reaching the wire:
 * the circuit breaker is open ({@code CallNotPermittedException} cause) or the bulkhead is full
 * ({@code BulkheadFullException} cause).
 *
 * <p>Deliberately NOT a subclass of {@link OpenTmfClientResponseException}: no HTTP exchange took
 * place, and the retry utilities ({@code SyncClientUtil.executeWithRetry},
 * {@code WebClientUtil.retry}) must never retry a rejected call — an open circuit means "stop
 * calling". Consumers typically map this exception to a 503 upstream-degraded response.</p>
 */
@Getter
public class OpenTmfClientResilienceException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 1L;

  /**
   * Id of the client whose resilience decoration rejected the call.
   */
  private final String clientId;

  public OpenTmfClientResilienceException(String clientId, String message, Throwable cause) {
    super(message, cause);
    this.clientId = clientId;
  }
}
