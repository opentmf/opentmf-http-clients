package org.opentmf.client.bearer.sync;

import static org.opentmf.client.bearer.util.BearerTokenUtil.SCOPE;
import static org.springframework.util.StringUtils.hasText;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.opentmf.client.bearer.exception.BearerTokenTransportException;
import org.opentmf.client.bearer.observe.TokenFetchListener;
import org.opentmf.client.bearer.observe.TokenFetchListener.Outcome;
import org.opentmf.client.bearer.util.TransportFailures;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.rest.util.SyncClientUtil;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.node.ObjectNode;

/**
 * Mints a bearer token over the client's {@link RestClient}. A mint is idempotent, so a
 * transport-level failure (see {@link TransportFailures}) is retried exactly once, immediately;
 * a second transport failure surfaces as {@link BearerTokenTransportException}. Retryable
 * statuses from the token endpoint are additionally retried per the client's
 * {@code num-retries} / {@code retry-wait-duration} / {@code max-retry-after} — the same contract
 * as the reactive twin — and each of those attempts owns its own single transport retry; other
 * status errors pass through unchanged. Every settled mint is reported to the
 * {@link TokenFetchListener} and, on success, logged at INFO with the issuer URL.
 */
@Slf4j
public class SyncTokenClientImpl {

  private static final int MAX_TRANSPORT_ATTEMPTS = 2;

  private final RestClient restClient;
  private final BearerAuthConfig config;
  private final TokenFetchListener listener;
  private final int numRetries;
  private final Duration retryWaitDuration;
  private final Duration maxRetryAfter;

  /** Without client properties: the transport retry only, no status retry. */
  public SyncTokenClientImpl(RestClient restClient, BearerAuthConfig config) {
    this(restClient, config, TokenFetchListener.noop());
  }

  /** Without client properties: the transport retry only, no status retry. */
  public SyncTokenClientImpl(RestClient restClient, BearerAuthConfig config,
      TokenFetchListener listener) {
    this(restClient, config, listener, 0, Duration.ZERO, SyncClientUtil.DEFAULT_MAX_RETRY_AFTER);
  }

  /** Retryable statuses are retried per the client's retry settings. */
  public SyncTokenClientImpl(RestClient restClient, ClientProperties properties,
      BearerAuthConfig config, TokenFetchListener listener) {
    this(restClient, config, listener, properties.getNumRetries(),
        properties.getRetryWaitDuration(), properties.getMaxRetryAfter());
  }

  private SyncTokenClientImpl(RestClient restClient, BearerAuthConfig config,
      TokenFetchListener listener, int numRetries, Duration retryWaitDuration,
      Duration maxRetryAfter) {
    this.restClient = restClient;
    this.config = config;
    this.listener = listener;
    this.numRetries = numRetries;
    this.retryWaitDuration = retryWaitDuration;
    this.maxRetryAfter = maxRetryAfter;
  }

  public ObjectNode getToken(URI tokenUrl, MultiValueMap<String, String> formData) {
    var scope = formData.get(SCOPE);
    log.debug("Will retrieve a new bearer token (sync) from url: {}, scope: {}, username: {}",
        tokenUrl, scope, formData.get(config.getUsernameField()));
    long start = System.nanoTime();
    var attempts = new AtomicInteger();

    try {
      var token = SyncClientUtil.executeWithRetry(
          () -> exchangeWithTransportRetry(tokenUrl, formData, attempts), numRetries,
          retryWaitDuration, 0.0d, maxRetryAfter);
      log.info("Bearer token minted from {} (scope: {}, attempt {}, {} ms)", tokenUrl, scope,
          attempts.get(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      listener.onTokenFetch(tokenUrl, attempts.get() == 1 ? Outcome.OK : Outcome.RETRIED);
      return token;
    } catch (RuntimeException e) {
      listener.onTokenFetch(tokenUrl, Outcome.FAILED);
      throw e;
    }
  }

  private ObjectNode exchangeWithTransportRetry(URI tokenUrl,
      MultiValueMap<String, String> formData, AtomicInteger attempts) {
    for (int attempt = 1; ; attempt++) {
      attempts.incrementAndGet();
      try {
        return exchange(tokenUrl, formData);
      } catch (RuntimeException e) {
        if (!TransportFailures.isTransportFailure(e)) {
          throw e;
        }
        if (attempt >= MAX_TRANSPORT_ATTEMPTS) {
          throw new BearerTokenTransportException(tokenUrl, attempt, e);
        }
        log.warn("Bearer token fetch from {} failed at the transport level ({}); retrying once",
            tokenUrl, TransportFailures.describeChain(e));
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
