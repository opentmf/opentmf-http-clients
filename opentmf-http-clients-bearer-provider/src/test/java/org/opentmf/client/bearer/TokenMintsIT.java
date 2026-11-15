package org.opentmf.client.bearer;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentmf.client.bearer.TokenPeer.Answer;
import org.opentmf.client.bearer.exception.BearerTokenException;
import org.opentmf.client.bearer.exception.BearerTokenTransportException;
import org.opentmf.client.bearer.model.TokenEntry;
import org.opentmf.client.bearer.observe.RecordingTokenFetchListener;
import org.opentmf.client.bearer.observe.TokenFetchListener.Outcome;
import org.opentmf.client.bearer.reactive.BearerTokenClientImpl;
import org.opentmf.client.bearer.reactive.BearerTokenServiceImpl;
import org.opentmf.client.bearer.sync.SyncBearerTokenServiceImpl;
import org.opentmf.client.bearer.sync.SyncTokenClientImpl;
import org.opentmf.client.bearer.util.TokenCacheUtil;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.rest.util.OpenTmfRestClientStatusHandler;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * How many token calls K concurrent callers of one token key make, measured at a plain-socket
 * token endpoint that holds each answer back, so every caller asks while the first mint is in
 * flight: in an outage (an error status, a transport failure), at recovery on an empty cache,
 * and when the cached token expires under load. Callers of one key join the one mint in flight
 * and share its outcome: one mint, whatever K. With {@code bearer-auth.failure-memory} set, a
 * retryable failure is remembered and the next callers fail at once; the token call's status
 * retries are jittered.
 */
class TokenMintsIT {

  private static final Duration RETRY_WAIT = Duration.ofMillis(50);
  /** The default {@code num-retries}: a mint at a retryable status makes 1 + 3 calls. */
  private static final int STATUS_CHAIN = 4;
  /** One call and its one transport retry. */
  private static final int TRANSPORT_CHAIN = 2;

  private static final String BEARER_TOKEN_EXCEPTION = BearerTokenException.class.getSimpleName();
  private static final String TRANSPORT_EXCEPTION =
      BearerTokenTransportException.class.getSimpleName();
  private static final long JITTER_BASE_MS = 400;

  private static TokenPeer peer;

  private RecordingTokenFetchListener listener;
  private AtomicLong ticker;
  private Cache<String, TokenEntry> cache;

  @BeforeAll
  static void startPeer() throws IOException {
    peer = TokenPeer.start();
  }

  @AfterAll
  static void stopPeer() throws IOException {
    peer.close();
  }

  @BeforeEach
  void setUp() {
    peer.reset();
    listener = new RecordingTokenFetchListener();
    ticker = new AtomicLong();
    cache = Caffeine.newBuilder()
        .expireAfter(TokenCacheUtil.tokenExpiry())
        .ticker(ticker::get)
        .build();
  }

  @ParameterizedTest(name = "{0}, K={1}")
  @CsvSource({"sync, 1", "sync, 8", "sync, 32", "reactive, 1", "reactive, 8", "reactive, 32"})
  void outage_errorStatus_oneMintForAllCallers(String type, int callers)
      throws InterruptedException {
    peer.answer(Answer.UNAVAILABLE, 100);

    var outcomes = callTogether(type, callers);

    assertThat(peer.calls()).isEqualTo(STATUS_CHAIN);
    assertThat(outcomes).hasSize(callers).containsOnly(BEARER_TOKEN_EXCEPTION);
    assertThat(listener.outcomes()).containsExactly(Outcome.FAILED);
  }

  @ParameterizedTest(name = "{0}, K={1}")
  @CsvSource({"sync, 1", "sync, 8", "sync, 32", "reactive, 1", "reactive, 8", "reactive, 32"})
  void outage_transportFailure_oneMintForAllCallers(String type, int callers)
      throws InterruptedException {
    peer.answer(Answer.CLOSE, 300);

    var outcomes = callTogether(type, callers);

    assertThat(peer.calls()).isEqualTo(TRANSPORT_CHAIN);
    assertThat(outcomes).hasSize(callers)
        .containsOnly(TRANSPORT_EXCEPTION);
    assertThat(listener.outcomes()).containsExactly(Outcome.FAILED);
  }

  @ParameterizedTest(name = "{0}, K={1}")
  @CsvSource({"sync, 1", "sync, 8", "sync, 32", "reactive, 1", "reactive, 8", "reactive, 32"})
  void recovery_emptyCache_oneMintForAllCallers(String type, int callers)
      throws InterruptedException {
    peer.answer(Answer.TOKEN, 300);

    var outcomes = callTogether(type, callers);

    assertThat(peer.calls()).isOne();
    assertThat(outcomes).hasSize(callers).containsOnly("tok");
    assertThat(listener.outcomes()).containsExactly(Outcome.OK);
  }

  @ParameterizedTest(name = "{0}, K={1}")
  @CsvSource({"sync, 1", "sync, 8", "sync, 32", "reactive, 1", "reactive, 8", "reactive, 32"})
  void expiryUnderLoad_oneMintForAllCallers(String type, int callers)
      throws InterruptedException {
    peer.answer(Answer.TOKEN, 300);
    var service = service(type);
    assertThat(callTogether(service, 1)).containsExactly("tok");
    assertThat(callTogether(service, callers)).hasSize(callers).containsOnly("tok");
    assertThat(peer.calls()).as("cached: no call while the token lives").isOne();

    // past the cache's lifetime of the token: expires_in x cache-safety-factor
    ticker.addAndGet(TimeUnit.SECONDS.toNanos(TokenPeer.EXPIRES_IN));
    var outcomes = callTogether(service, callers);

    assertThat(peer.calls()).isEqualTo(2);
    assertThat(outcomes).hasSize(callers).containsOnly("tok");
    assertThat(listener.outcomes()).containsExactly(Outcome.OK, Outcome.OK);
  }

  // ---- (b) the failure memory, opt-in

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sync", "reactive"})
  void failureMemory_errorStatus_nextCallersFailAtOnce(String type) throws InterruptedException {
    peer.answer(Answer.UNAVAILABLE, 100);
    var retrieval = service(type, bearer -> bearer.setFailureMemory(Duration.ofSeconds(30)));

    assertThat(callTogether(retrieval, 8)).containsOnly(BEARER_TOKEN_EXCEPTION);
    assertThat(callTogether(retrieval, 8)).hasSize(8).containsOnly(BEARER_TOKEN_EXCEPTION);

    assertThat(peer.calls()).as("the second wave is remembered: no call").isEqualTo(STATUS_CHAIN);
    assertThat(listener.outcomes()).containsExactly(Outcome.FAILED);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sync", "reactive"})
  void failureMemory_transportFailure_nextCallersFailAtOnce(String type)
      throws InterruptedException {
    peer.answer(Answer.CLOSE, 300);
    var retrieval = service(type, bearer -> bearer.setFailureMemory(Duration.ofSeconds(30)));

    assertThat(callTogether(retrieval, 8)).containsOnly(TRANSPORT_EXCEPTION);
    assertThat(callTogether(retrieval, 8)).hasSize(8).containsOnly(TRANSPORT_EXCEPTION);

    assertThat(peer.calls()).isEqualTo(TRANSPORT_CHAIN);
    assertThat(listener.outcomes()).containsExactly(Outcome.FAILED);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sync", "reactive"})
  void failureMemory_refusedCredential_isNotRemembered(String type) throws InterruptedException {
    peer.answer(Answer.REFUSED, 100);
    var retrieval = service(type, bearer -> bearer.setFailureMemory(Duration.ofSeconds(30)));

    assertThat(callTogether(retrieval, 8)).containsOnly(BEARER_TOKEN_EXCEPTION);
    assertThat(callTogether(retrieval, 8)).hasSize(8).containsOnly(BEARER_TOKEN_EXCEPTION);

    assertThat(peer.calls()).as("a 401 is not retried and not remembered").isEqualTo(2);
    assertThat(listener.outcomes()).containsExactly(Outcome.FAILED, Outcome.FAILED);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sync", "reactive"})
  void failureMemory_unset_theNextCallersMintAgain(String type) throws InterruptedException {
    peer.answer(Answer.UNAVAILABLE, 100);
    var retrieval = service(type, bearer -> { });

    assertThat(callTogether(retrieval, 8)).containsOnly(BEARER_TOKEN_EXCEPTION);
    assertThat(callTogether(retrieval, 8)).hasSize(8).containsOnly(BEARER_TOKEN_EXCEPTION);

    assertThat(peer.calls()).isEqualTo(2 * STATUS_CHAIN);
    assertThat(listener.outcomes()).containsExactly(Outcome.FAILED, Outcome.FAILED);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sync", "reactive"})
  void failureMemory_clearCache_forgetsTheFailure(String type) throws InterruptedException {
    peer.answer(Answer.UNAVAILABLE, 100);
    var retrieval = service(type, bearer -> bearer.setFailureMemory(Duration.ofSeconds(30)));
    assertThat(callTogether(retrieval, 8)).containsOnly(BEARER_TOKEN_EXCEPTION);

    retrieval.clearCache().run();
    peer.answer(Answer.TOKEN, 100);

    assertThat(callTogether(retrieval, 8)).hasSize(8).containsOnly("tok");
    assertThat(peer.calls()).isEqualTo(STATUS_CHAIN + 1);
  }

  // ---- (c) jitter on the token call's status retries

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sync", "reactive"})
  void statusRetries_areJittered(String type) throws InterruptedException {
    peer.answer(Answer.UNAVAILABLE, 0);
    var retrieval = service(type, bearer -> bearer.setNumRetries(1),
        client -> client.setRetryWaitDuration(Duration.ofMillis(JITTER_BASE_MS)));
    List<Long> waits = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      peer.reset();
      assertThat(callTogether(retrieval, 1)).containsOnly(BEARER_TOKEN_EXCEPTION);
      var arrivals = peer.arrivals();
      assertThat(arrivals).hasSize(2);
      waits.add(TimeUnit.NANOSECONDS.toMillis(arrivals.get(1) - arrivals.get(0)));
    }

    // the wait is the backoff spread by up to half of it either way
    assertThat(waits).allSatisfy(wait -> assertThat(wait)
        .isBetween(JITTER_BASE_MS / 2 - 10, JITTER_BASE_MS * 3 / 2 + 100));
    assertThat(Collections.max(waits) - Collections.min(waits))
        .as("the waits %s are spread", waits)
        .isGreaterThan(20L);
  }

  private List<String> callTogether(String type, int callers) throws InterruptedException {
    return callTogether(service(type, bearer -> { }), callers);
  }

  /** Every caller asks at the same moment; each outcome is the token or the failure's type. */
  private static List<String> callTogether(Retrieval retrieval, int callers)
      throws InterruptedException {
    var outcomes = new ConcurrentLinkedQueue<String>();
    var gate = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < callers; i++) {
      var thread = new Thread(() -> {
        try {
          gate.await();
          outcomes.add(retrieval.token().get()
              .onErrorResume(e -> Mono.just(e.getClass().getSimpleName()))
              .block(Duration.ofSeconds(30)));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      thread.start();
      threads.add(thread);
    }
    gate.countDown();
    for (var thread : threads) {
      thread.join(TimeUnit.SECONDS.toMillis(60));
    }
    return List.copyOf(outcomes);
  }

  private Retrieval service(String type) {
    return service(type, bearer -> { });
  }

  private Retrieval service(String type, Consumer<BearerAuthConfig> bearer) {
    return service(type, bearer, client -> { });
  }

  /** A token retrieval of the given type, as a {@code Mono} for both. */
  private Retrieval service(String type, Consumer<BearerAuthConfig> bearer,
      Consumer<ClientProperties> client) {
    var config = new BearerAuthConfig();
    config.setTokenUrl(peer.tokenUrl());
    config.setClientId("client1");
    config.setClientSecret("secret1");
    config.setFormData(Map.of("grant_type", "client_credentials"));
    bearer.accept(config);
    var properties = new ClientProperties();
    properties.setRetryWaitDuration(RETRY_WAIT);
    properties.setBearerAuth(config);
    client.accept(properties);
    if (type.equals("sync")) {
      var restClient = RestClient.builder()
          .defaultStatusHandler(HttpStatusCode::isError,
              OpenTmfRestClientStatusHandler.errorHandler())
          .build();
      var service = new SyncBearerTokenServiceImpl(config, cache,
          new SyncTokenClientImpl(restClient, properties, config, listener));
      return new Retrieval(() -> Mono.fromCallable(service::getToken), service::clearCache);
    }
    var service = new BearerTokenServiceImpl(config, cache,
        new BearerTokenClientImpl(properties, config, WebClient.builder().build(), listener));
    return new Retrieval(() -> Mono.defer(service::getToken), service::clearCache);
  }

  /** A token service of either type: its token, and its {@code clearCache}. */
  private record Retrieval(Supplier<Mono<String>> token, Runnable clearCache) {
  }
}
