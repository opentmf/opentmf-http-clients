package org.opentmf.client.bearer.reactive;

import static org.opentmf.client.bearer.util.BearerTokenUtil.SCOPE;
import static org.springframework.util.StringUtils.hasText;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.opentmf.client.bearer.exception.BearerTokenException;
import org.opentmf.client.bearer.exception.BearerTokenTransportException;
import org.opentmf.client.bearer.observe.TokenFetchListener;
import org.opentmf.client.bearer.observe.TokenFetchListener.Outcome;
import org.opentmf.client.bearer.util.TransportFailures;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.reactive.util.WebClientUtil;
import org.opentmf.commons.util.JacksonUtil;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import tools.jackson.databind.node.ObjectNode;

/**
 * Mints a bearer token over the client's {@link WebClient}. Mirrors the sync twin: a
 * transport-level failure is retried exactly once per attempt, a second one surfaces as
 * {@link BearerTokenTransportException}; retryable statuses are additionally retried per the
 * client's {@code num-retries} / {@code retry-wait-duration}. Every settled mint is reported to
 * the {@link TokenFetchListener} and, on success, logged at INFO with the issuer URL.
 */
@Slf4j
public class BearerTokenClientImpl implements BearerTokenClient {

  private final ClientProperties properties;
  private final BearerAuthConfig bearerConfig;
  private final WebClient webClient;
  private final TokenFetchListener listener;

  public BearerTokenClientImpl(ClientProperties properties, BearerAuthConfig bearerConfig,
      WebClient webClient) {
    this(properties, bearerConfig, webClient, TokenFetchListener.noop());
  }

  public BearerTokenClientImpl(ClientProperties properties, BearerAuthConfig bearerConfig,
      WebClient webClient, TokenFetchListener listener) {
    this.properties = properties;
    this.bearerConfig = bearerConfig;
    this.webClient = webClient;
    this.listener = listener;
  }

  @Override
  public Mono<ObjectNode> retrieveToken(URI tokenUrl, MultiValueMap<String, String> formData) {
    var scope = formData.get(SCOPE);
    log.debug("Will retrieve a new bearer token from url: {}, scope: {}, username: {}",
        tokenUrl, scope, formData.get(bearerConfig.getUsernameField()));
    return Mono.defer(() -> {
      var attempts = new AtomicInteger();
      long start = System.nanoTime();
      return post(tokenUrl, BodyInserters.fromFormData(formData))
          .doOnSubscribe(s -> attempts.incrementAndGet())
          .retryWhen(Retry.max(1)
              .filter(BearerTokenClientImpl::isTransportFailure)
              .doBeforeRetry(signal -> {
                var root = TransportFailures.rootCause(signal.failure());
                log.warn("Bearer token fetch from {} failed at the transport level ({}: {}); "
                    + "retrying once", tokenUrl, root.getClass().getSimpleName(),
                    root.getMessage());
              })
              .onRetryExhaustedThrow((spec, signal) -> new BearerTokenTransportException(
                  tokenUrl, (int) signal.totalRetries() + 1, signal.failure())))
          .retryWhen(WebClientUtil.retry(properties.getNumRetries(),
              properties.getRetryWaitDuration(), 0.0d, properties.getMaxRetryAfter()))
          .doOnSuccess(token -> {
            log.info("Bearer token minted from {} (scope: {}, attempt {}, {} ms)", tokenUrl,
                scope, attempts.get(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            listener.onTokenFetch(tokenUrl, attempts.get() == 1 ? Outcome.OK : Outcome.RETRIED);
          })
          .doOnError(e -> listener.onTokenFetch(tokenUrl, Outcome.FAILED));
    });
  }

  private Mono<ObjectNode> post(URI url, BodyInserters.FormInserter<String> tokenRequestForm) {
    var clientId = bearerConfig.getClientId();
    var clientSecret = bearerConfig.getClientSecret();

    return webClient
        .post()
        .uri(url)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .accept(MediaType.APPLICATION_JSON)
        .body(tokenRequestForm)
        .headers(headers -> {
          if (hasText(clientId) && hasText(clientSecret)) {
            headers.setBasicAuth(clientId, clientSecret);
          }
        })
        .retrieve()
        .onStatus(HttpStatusCode::isError,
            clientResponse -> WebClientUtil.handleError(clientResponse, BearerTokenException.class))
        .bodyToMono(String.class)
        .map(body -> (ObjectNode) JacksonUtil.jsonToTree(body));
  }

  /**
   * The reactive shape of {@link TransportFailures#isTransportFailure}: WebFlux wraps request
   * I/O errors in {@link WebClientRequestException}, and a body that fails under a successful
   * status ("200 OK … but response failed with cause: IOException") in a
   * {@link WebClientResponseException} whose status is not an error — that one is transport too.
   * A {@code WebClientResponseException} with an error status is the identity provider's answer
   * and is never retried here.
   */
  private static boolean isTransportFailure(Throwable throwable) {
    if (throwable instanceof WebClientResponseException e) {
      return !e.getStatusCode().isError() && TransportFailures.hasIoCause(e);
    }
    return throwable instanceof WebClientRequestException
        || TransportFailures.isTransportFailure(throwable);
  }
}
