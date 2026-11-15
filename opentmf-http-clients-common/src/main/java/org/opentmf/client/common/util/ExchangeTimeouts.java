package org.opentmf.client.common.util;

import java.net.SocketTimeoutException;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * The failure a client's {@code exchange-timeout} raises when it passes before the response was
 * fully received: a {@link SocketTimeoutException}, so a caller that handles a read timeout
 * handles this one the same way, told apart by its message, which names the bound. It reaches
 * the caller as the cause of the client's transport failure, never of a status exception.
 */
public final class ExchangeTimeouts {

  /** The start of the message, which names the bound in milliseconds next. */
  public static final String MESSAGE_PREFIX = "exchange-timeout of ";

  private ExchangeTimeouts() {
  }

  /**
   * @param exchangeTimeout the bound that passed
   * @param cause the failure the cut caused on the connection, if any
   */
  public static SocketTimeoutException exceeded(Duration exchangeTimeout,
      @Nullable Throwable cause) {
    var failure = new SocketTimeoutException(MESSAGE_PREFIX + exchangeTimeout.toMillis()
        + " ms passed before the response was fully received");
    if (cause != null) {
      failure.initCause(cause);
    }
    return failure;
  }
}
