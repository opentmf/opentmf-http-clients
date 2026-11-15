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
import org.opentmf.client.bearer.util.BearerTokenUtil;
import org.opentmf.client.bearer.util.TokenStatusFailures;
import org.opentmf.client.bearer.util.TransportFailures;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.util.HttpClientUtil;
import org.opentmf.client.common.util.TokenCallProperties;
import org.opentmf.client.common.util.TokenUrls;
import org.opentmf.client.reactive.util.WebClientUtil;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
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
 * client's {@code num-retries} (or {@code bearer-auth.num-retries} when set) /
 * {@code retry-wait-duration}. Every error status from the token endpoint, a 404 included,
 * surfaces as a {@link BearerTokenException}: the status and
 * headers kept, the body withheld. Every settled mint is reported to
 * the {@link TokenFetchListener} and, on success, logged at INFO with the issuer URL. A success
 * answer without a usable token fails with {@code BearerTokenResponseException} and is not
 * retried.
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
    this.properties = TokenCallProperties.of(properties);
    this.bearerConfig = bearerConfig;
    this.webClient = webClient;
    this.listener = listener;
  }

  @Override
  public Mono<ObjectNode> retrieveToken(URI tokenUrl, MultiValueMap<String, String> formData) {
    var scope = formData.get(SCOPE);
    var shownUrl = TokenUrls.shown(tokenUrl);
    log.debug("Will retrieve a new bearer token from url: {}, scope: {}, username: {}",
        shownUrl, scope, formData.get(bearerConfig.getUsernameField()));
    return Mono.defer(() -> {
      var attempts = new AtomicInteger();
      long start = System.nanoTime();
      return post(tokenUrl, BodyInserters.fromFormData(formData))
          .doOnSubscribe(s -> attempts.incrementAndGet())
          .retryWhen(Retry.max(1)
              .filter(BearerTokenClientImpl::isTransportFailure)
              .doBeforeRetry(signal -> log.warn(
                  "Bearer token fetch from {} failed at the transport level ({}); retrying once",
                  shownUrl,
                  TokenUrls.scrubbed(TransportFailures.describeChain(signal.failure()), tokenUrl)))
              .onRetryExhaustedThrow((spec, signal) -> new BearerTokenTransportException(
                  tokenUrl, (int) signal.totalRetries() + 1, signal.failure())))
          .retryWhen(WebClientUtil.retry(properties.getNumRetries(),
              properties.getRetryWaitDuration(), BearerTokenUtil.TOKEN_RETRY_JITTER,
              properties.getMaxRetryAfter()))
          .doOnSuccess(token -> {
            log.info("Bearer token minted from {} (scope: {}, attempt {}, {} ms)", shownUrl,
                scope, attempts.get(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            listener.onTokenFetch(shownUrl, attempts.get() == 1 ? Outcome.OK : Outcome.RETRIED);
          })
          .doOnError(e -> listener.onTokenFetch(shownUrl, Outcome.FAILED));
    });
  }

  private Mono<ObjectNode> post(URI url, BodyInserters.FormInserter<String> tokenRequestForm) {
    var clientId = bearerConfig.getClientId();
    var clientSecret = bearerConfig.getClientSecret();

    return webClient
        .post()
        .uri(url)
        // bearer-auth.headers first, so the protocol's own Content-Type and Basic auth win
        .headers(headers -> headers.setAll(bearerConfig.getHeaders()))
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .accept(MediaType.APPLICATION_JSON)
        .body(tokenRequestForm)
        .headers(headers -> {
          if (hasText(clientId) && hasText(clientSecret)) {
            headers.setBasicAuth(clientId, clientSecret);
          }
        })
        .retrieve()
        // a WebClient without the library's error filter: the status reaches this handler
        .onStatus(HttpStatusCode::isError,
            clientResponse -> tokenStatusFailure(url, clientResponse))
        // an entity, not the body: an empty answer still emits, with its status
        .toEntity(String.class)
        .map(answer -> BearerTokenUtil.readTokenResponse(url, answer.getStatusCode(),
            answer.getBody(), bearerConfig.getTokenField()))
        // the library's WebClient: its error filter raised the status first
        .onErrorMap(OpenTmfClientResponseException.class,
            e -> TokenStatusFailures.toTokenFailure(url, e));
  }

  private static Mono<Throwable> tokenStatusFailure(URI url, ClientResponse clientResponse) {
    var status = clientResponse.statusCode();
    var headers = clientResponse.headers().asHttpHeaders();
    return clientResponse.bodyToMono(String.class)
        .defaultIfEmpty("")
        .map(body -> TokenStatusFailures.of(url, status, headers,
            HttpClientUtil.retryAfterFor(status, headers), body));
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
