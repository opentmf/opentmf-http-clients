package org.opentmf.client.common.exception;

import java.io.Serial;
import java.net.SocketTimeoutException;
import java.time.Duration;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

/**
 * The client's {@code exchange-timeout} passed before the response was fully received: the
 * headers had not arrived, or the body was still arriving. The exchange was cut, and nothing of
 * it reaches the caller as an answer.
 *
 * <p>A {@link SocketTimeoutException}, so a caller that handles a read timeout handles this one
 * the same way. It reaches the caller as the cause of the client's transport failure: the
 * {@code ResourceAccessException} or the body-extraction {@code RestClientException} of the
 * blocking clients, the {@code WebClientRequestException} of the reactive one; never as the
 * cause of a status exception.</p>
 */
@Getter
public class OpenTmfClientExchangeTimeoutException extends SocketTimeoutException {

  @Serial
  private static final long serialVersionUID = 1L;

  /** The bound that passed. */
  private final Duration exchangeTimeout;

  /**
   * @param exchangeTimeout the bound that passed
   * @param cause the failure the cut caused on the connection, if any
   */
  public OpenTmfClientExchangeTimeoutException(Duration exchangeTimeout,
      @Nullable Throwable cause) {
    super("exchange-timeout of " + exchangeTimeout.toMillis()
        + " ms passed before the response was fully received");
    this.exchangeTimeout = exchangeTimeout;
    if (cause != null) {
      initCause(cause);
    }
  }
}
