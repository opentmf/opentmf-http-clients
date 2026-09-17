package org.opentmf.client.bearer.exception;

import java.io.Serial;
import java.net.URI;
import lombok.Getter;

/**
 * Thrown when a bearer-token mint failed at the transport level on every attempt — no usable
 * HTTP response came back from the token endpoint (connection dropped, premature EOF, reset,
 * I/O timeout), including after the token client's single automatic retry.
 *
 * <p>Deliberately NOT a subclass of {@code OpenTmfClientResponseException}: there is no HTTP
 * status to report, and the retry utilities ({@code SyncClientUtil.executeWithRetry},
 * {@code WebClientUtil.retry}) must not retry it further — the token client already did.
 * Consumers typically map it to a 503 (identity provider unreachable); a status-bearing
 * {@link BearerTokenException} / {@code OpenTmfClientResponseException} from the token endpoint
 * is the "identity provider answered, but with an error" case and is mapped separately.</p>
 */
@Getter
public class BearerTokenTransportException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 1L;

  /** The token endpoint that could not be reached or did not complete its response. */
  private final URI tokenUrl;

  /** Attempts made before giving up, the automatic retry included. */
  private final int attempts;

  public BearerTokenTransportException(URI tokenUrl, int attempts, Throwable cause) {
    super("Bearer token fetch from " + tokenUrl + " failed at the transport level after "
        + attempts + " attempts: " + rootMessage(cause), cause);
    this.tokenUrl = tokenUrl;
    this.attempts = attempts;
  }

  private static String rootMessage(Throwable cause) {
    Throwable root = cause;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    return root.getClass().getSimpleName() + ": " + root.getMessage();
  }
}
