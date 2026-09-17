package org.opentmf.client.bearer.util;

import java.io.IOException;
import org.opentmf.client.bearer.exception.BearerTokenTransportException;
import org.opentmf.client.common.exception.OpenTmfClientResilienceException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Classifies a failed token fetch as transport-level or not. Transport-level failures are the
 * ones a single immediate retry can fix: the connection died under the request — a keep-alive
 * connection reused after the peer had closed it, a reset, a premature EOF, an I/O timeout.
 * The JDK HttpClient retries such stale connections itself only for GET/HEAD; a token mint is
 * a POST, so the token clients own that retry.
 *
 * <p>A failure is transport-level when it is not status-bearing (the identity provider answered,
 * so retrying blindly is wrong) and not a resilience rejection (an open breaker means "stop
 * calling"), and an {@link IOException} sits somewhere in its cause chain. That single rule
 * covers Spring's {@code ResourceAccessException} (headers never arrived), its
 * {@code RestClientException} wrapper around a body-stage {@code IOException("closed")}, and
 * Reactor Netty's {@code PrematureCloseException}. Malformed JSON is not transport: Jackson 3's
 * exceptions are unchecked and carry no {@code IOException}.</p>
 */
public final class TransportFailures {

  private TransportFailures() {
  }

  public static boolean isTransportFailure(Throwable throwable) {
    if (throwable instanceof OpenTmfClientResponseException
        || throwable instanceof RestClientResponseException
        || throwable instanceof OpenTmfClientResilienceException
        || throwable instanceof BearerTokenTransportException) {
      return false;
    }
    return hasIoCause(throwable);
  }

  /** Whether an {@link IOException} sits anywhere in the cause chain, the throwable included. */
  public static boolean hasIoCause(Throwable throwable) {
    for (Throwable t = throwable; t != null; t = t.getCause()) {
      if (t instanceof IOException) {
        return true;
      }
    }
    return false;
  }

  /** The innermost cause, for one-line log messages. */
  public static Throwable rootCause(Throwable throwable) {
    Throwable root = throwable;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    return root;
  }
}
