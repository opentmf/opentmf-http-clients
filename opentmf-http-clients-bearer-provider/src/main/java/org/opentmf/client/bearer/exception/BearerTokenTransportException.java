package org.opentmf.client.bearer.exception;

import java.io.Serial;
import java.net.URI;
import lombok.Getter;
import org.opentmf.client.bearer.util.TransportFailures;
import org.opentmf.client.common.util.TokenUrls;

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
 *
 * <p>The last failure is chained as the cause, and the message carries its cause chain. When the
 * configured token URL has a user part ({@code http://user:password@idp/token}), the cause is NOT
 * chained: the HTTP client's own exceptions quote the URL with that part, so a printed stack
 * trace would show it. The message then carries the same cause chain, class names and texts,
 * without the user part, so the diagnosis is kept.</p>
 */
@Getter
public class BearerTokenTransportException extends RuntimeException {

  @Serial
  private static final long serialVersionUID = 1L;

  /**
   * The token endpoint that could not be reached or did not complete its response, without a
   * user part of the configured URL.
   */
  private final URI tokenUrl;

  /** Attempts made before giving up, the automatic retry included. */
  private final int attempts;

  public BearerTokenTransportException(URI tokenUrl, int attempts, Throwable cause) {
    super("Bearer token fetch from " + TokenUrls.shown(tokenUrl)
        + " failed at the transport level after " + attempts + " attempts: "
        + TokenUrls.scrubbed(TransportFailures.describeChain(cause), tokenUrl),
        tokenUrl.getRawUserInfo() == null ? cause : null);
    this.tokenUrl = TokenUrls.shown(tokenUrl);
    this.attempts = attempts;
  }
}
