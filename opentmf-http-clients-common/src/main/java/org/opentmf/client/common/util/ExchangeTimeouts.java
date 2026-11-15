package org.opentmf.client.common.util;

import java.net.SocketTimeoutException;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * The failure a client's {@code exchange-timeout} raises when it passes before the response was
 * fully received: a {@link SocketTimeoutException}, so a caller that handles a read timeout
 * handles this one the same way. It reaches the caller as the cause of the client's transport
 * failure, never of a status exception.
 *
 * <p>Its message names the bound for a person reading a log. The message text is informational,
 * NOT a contract: do not match on it. A caller cannot tell from the exception whether
 * {@code exchange-timeout} or {@code response-timeout} cut the exchange.</p>
 */
public final class ExchangeTimeouts {

  private ExchangeTimeouts() {
  }

  /**
   * @param exchangeTimeout the bound that passed
   * @param cause the failure the cut caused on the connection, if any
   */
  public static SocketTimeoutException exceeded(Duration exchangeTimeout,
      @Nullable Throwable cause) {
    var failure = new SocketTimeoutException("exchange-timeout of " + exchangeTimeout.toMillis()
        + " ms passed before the response was fully received");
    if (cause != null) {
      failure.initCause(cause);
    }
    return failure;
  }
}
