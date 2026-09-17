package org.opentmf.client.bearer.sync;

import static org.opentmf.client.bearer.util.BearerTokenUtil.SCOPE;
import static org.springframework.util.StringUtils.hasText;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.opentmf.client.bearer.exception.BearerTokenTransportException;
import org.opentmf.client.bearer.observe.TokenFetchListener;
import org.opentmf.client.bearer.observe.TokenFetchListener.Outcome;
import org.opentmf.client.bearer.util.TransportFailures;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.node.ObjectNode;

/**
 * Mints a bearer token over the client's {@link RestClient}. A mint is idempotent, so a
 * transport-level failure (see {@link TransportFailures}) is retried exactly once, immediately;
 * a second transport failure surfaces as {@link BearerTokenTransportException}. Status errors
 * from the token endpoint pass through unchanged. Every settled mint is reported to the
 * {@link TokenFetchListener} and, on success, logged at INFO with the issuer URL.
 */
@Slf4j
public class SyncTokenClientImpl {

  private static final int MAX_ATTEMPTS = 2;

  private final RestClient restClient;
  private final BearerAuthConfig config;
  private final TokenFetchListener listener;

  public SyncTokenClientImpl(RestClient restClient, BearerAuthConfig config) {
    this(restClient, config, TokenFetchListener.noop());
  }

  public SyncTokenClientImpl(RestClient restClient, BearerAuthConfig config,
      TokenFetchListener listener) {
    this.restClient = restClient;
    this.config = config;
    this.listener = listener;
  }

  public ObjectNode getToken(URI tokenUrl, MultiValueMap<String, String> formData) {
    var scope = formData.get(SCOPE);
    log.debug("Will retrieve a new bearer token (sync) from url: {}, scope: {}, username: {}",
        tokenUrl, scope, formData.get(config.getUsernameField()));
    long start = System.nanoTime();

    for (int attempt = 1; ; attempt++) {
      try {
        var token = exchange(tokenUrl, formData);
        log.info("Bearer token minted from {} (scope: {}, attempt {}, {} ms)", tokenUrl, scope,
            attempt, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        listener.onTokenFetch(tokenUrl, attempt == 1 ? Outcome.OK : Outcome.RETRIED);
        return token;
      } catch (RuntimeException e) {
        if (!TransportFailures.isTransportFailure(e)) {
          listener.onTokenFetch(tokenUrl, Outcome.FAILED);
          throw e;
        }
        if (attempt >= MAX_ATTEMPTS) {
          listener.onTokenFetch(tokenUrl, Outcome.FAILED);
          throw new BearerTokenTransportException(tokenUrl, attempt, e);
        }
        var root = TransportFailures.rootCause(e);
        log.warn("Bearer token fetch from {} failed at the transport level ({}: {}); "
            + "retrying once", tokenUrl, root.getClass().getSimpleName(), root.getMessage());
      }
    }
  }

  private ObjectNode exchange(URI tokenUrl, MultiValueMap<String, String> formData) {
    var requestSpec = restClient.post()
        .uri(tokenUrl)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(formData);

    if (hasText(config.getClientId()) && hasText(config.getClientSecret())) {
      requestSpec.headers(h -> h.setBasicAuth(config.getClientId(), config.getClientSecret()));
    }

    return requestSpec.retrieve().body(ObjectNode.class);
  }
}
