package org.opentmf.client.bearer.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.opentmf.client.bearer.exception.BearerTokenException;
import org.opentmf.client.bearer.exception.BearerTokenResponseException;
import org.opentmf.client.bearer.exception.BearerTokenResponseException.Reason;
import org.opentmf.client.bearer.exception.BearerTokenTransportException;
import org.opentmf.client.bearer.model.TokenEntry;
import org.opentmf.client.common.model.ClientProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import tools.jackson.databind.json.JsonMapper;

class TokenMintsTest {

  private static final String KEY = "key";
  private static final URI TOKEN_URL = URI.create("http://idp/token");
  private static final Duration MEMORY = Duration.ofSeconds(10);
  private static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(30);
  private static final Duration REFUSAL = Duration.ofSeconds(10);

  private final AtomicLong now = new AtomicLong(1_000);
  private Cache<String, TokenEntry> cache;
  private TokenMints mints;
  private TokenMints remembering;
  private TokenMints refusing;

  @BeforeEach
  void setUp() {
    cache = Caffeine.newBuilder().build();
    mints = new TokenMints(cache, null, MAX_RETRY_AFTER, now::get);
    remembering = new TokenMints(cache, MEMORY, MAX_RETRY_AFTER, now::get);
    refusing = new TokenMints(cache, null, REFUSAL, MAX_RETRY_AFTER, now::get);
  }

  // ---- one mint per key, sync

  @Test
  void get_mintsOnThisThread_andCachesTheToken() {
    var minter = new AtomicReference<Thread>();
    var entry = mints.get(KEY, () -> {
      minter.set(Thread.currentThread());
      return entry("t1");
    });

    assertThat(entry).isEqualTo(entry("t1"));
    assertThat(minter.get()).isSameAs(Thread.currentThread());
    assertThat(cache.getIfPresent(KEY)).isEqualTo(entry("t1"));
  }

  @Test
  void get_aTokenCachedMeanwhile_isServedWithoutAMint() {
    cache.put(KEY, entry("cached"));

    var entry = mints.get(KEY, () -> {
      throw new AssertionError("no mint");
    });

    assertThat(entry).isEqualTo(entry("cached"));
  }

  @Test
  void get_aCallerOfAKeyInFlight_waitsForThatMintAndSharesItsToken() throws Exception {
    var release = new CountDownLatch(1);
    var mintsMade = new AtomicInteger();
    var owner = runAsync(() -> mints.get(KEY, () -> {
      mintsMade.incrementAndGet();
      await(release);
      return entry("t1");
    }));
    awaitInFlight();

    var joiner = joining(() -> mints.get(KEY, () -> {
      mintsMade.incrementAndGet();
      return entry("t2");
    }));
    release.countDown();

    assertThat(owner.get(5, TimeUnit.SECONDS)).isEqualTo(entry("t1"));
    assertThat(joiner.get(5, TimeUnit.SECONDS)).isEqualTo(entry("t1"));
    assertThat(mintsMade).hasValue(1);
  }

  @Test
  void get_aCallerOfAFailedMint_getsItsOwnCopyOfTheFailure() {
    var release = new CountDownLatch(1);
    var failure = status(503, Duration.ofSeconds(2));
    var owner = runAsync(() -> mints.get(KEY, () -> {
      await(release);
      throw failure;
    }));
    awaitInFlight();
    var joiner = joining(() -> mints.get(KEY, () -> entry("never")));
    release.countDown();

    assertThatThrownBy(() -> owner.get(5, TimeUnit.SECONDS)).cause().isSameAs(failure);
    assertThatThrownBy(() -> joiner.get(5, TimeUnit.SECONDS))
        .cause()
        .isInstanceOf(BearerTokenException.class)
        .isNotSameAs(failure)
        .hasMessage(failure.getMessage())
        .satisfies(copy -> {
          var token = (BearerTokenException) copy;
          assertThat(token.getStatusCode()).isEqualTo(failure.getStatusCode());
          assertThat(token.getRetryAfter()).isEqualTo(Duration.ofSeconds(2));
          assertThat(token.getHeaders()).isEqualTo(failure.getHeaders());
          assertThat(token.getOauthError()).isEqualTo("temporarily_unavailable");
          assertThat(token.getTokenUrl()).isEqualTo(TOKEN_URL);
        });
    assertThat(cache.getIfPresent(KEY)).isNull();
  }

  @Test
  void get_aMintThatEndsInAnError_releasesTheCallersWaitingForIt() {
    var release = new CountDownLatch(1);
    var owner = runAsync(() -> mints.get(KEY, () -> {
      await(release);
      throw new StackOverflowError("deep");
    }));
    awaitInFlight();
    var joiner = joining(() -> mints.get(KEY, () -> entry("never")));
    release.countDown();

    assertThatThrownBy(() -> owner.get(5, TimeUnit.SECONDS))
        .hasCauseInstanceOf(StackOverflowError.class);
    // a future failed with a CancellationException rethrows it as it is
    assertThatThrownBy(() -> joiner.get(5, TimeUnit.SECONDS))
        .isInstanceOf(CancellationException.class);
  }

  @Test
  void get_aWaitingCallerInterrupted_failsAndKeepsItsInterrupt() throws Exception {
    var release = new CountDownLatch(1);
    var owner = runAsync(() -> mints.get(KEY, () -> {
      await(release);
      return entry("t1");
    }));
    awaitInFlight();
    var interruptKept = new AtomicReference<Boolean>();
    var failure = new AtomicReference<Throwable>();
    var joiner = new Thread(() -> {
      Thread.currentThread().interrupt();
      try {
        mints.get(KEY, () -> entry("never"));
      } catch (RuntimeException e) {
        failure.set(e);
      }
      interruptKept.set(Thread.currentThread().isInterrupted());
    });
    joiner.start();
    joiner.join(5_000);
    release.countDown();

    assertThat(failure.get()).isInstanceOf(IllegalStateException.class)
        .hasCauseInstanceOf(InterruptedException.class);
    assertThat(interruptKept.get()).isTrue();
    assertThat(owner.get(5, TimeUnit.SECONDS)).isEqualTo(entry("t1"));
  }

  // ---- one mint per key, async

  @Test
  void getAsync_aCallerOfAKeyInFlight_sharesTheOneMint() throws Exception {
    var minting = new CompletableFuture<TokenEntry>();
    var mintsMade = new AtomicInteger();
    var first = mints.getAsync(KEY, () -> {
      mintsMade.incrementAndGet();
      return minting;
    });
    var second = mints.getAsync(KEY, () -> {
      mintsMade.incrementAndGet();
      return CompletableFuture.completedFuture(entry("t2"));
    });

    minting.complete(entry("t1"));

    assertThat(first.get()).isEqualTo(entry("t1"));
    assertThat(second.get()).isEqualTo(entry("t1"));
    assertThat(mintsMade).hasValue(1);
    assertThat(cache.getIfPresent(KEY)).isEqualTo(entry("t1"));
  }

  @Test
  void getAsync_aCallerThatCancels_cancelsNeitherTheMintNorTheOthers() throws Exception {
    var minting = new CompletableFuture<TokenEntry>();
    var first = mints.getAsync(KEY, () -> minting);
    var second = mints.getAsync(KEY, () -> minting);

    first.cancel(true);
    minting.complete(entry("t1"));

    assertThat(minting).isNotCancelled();
    assertThat(second.get()).isEqualTo(entry("t1"));
    assertThat(cache.getIfPresent(KEY)).isEqualTo(entry("t1"));
  }

  @Test
  void getAsync_eachCallerOfAFailedMint_getsItsOwnCopy() {
    var minting = new CompletableFuture<TokenEntry>();
    var first = mints.getAsync(KEY, () -> minting);
    var second = mints.getAsync(KEY, () -> minting);
    var failure = transport();

    minting.completeExceptionally(new CompletionException(failure));

    var firstFailure = failureOf(first);
    var secondFailure = failureOf(second);
    assertThat(firstFailure).isInstanceOf(BearerTokenTransportException.class)
        .hasMessage(failure.getMessage()).isNotSameAs(failure);
    assertThat(secondFailure).isInstanceOf(BearerTokenTransportException.class)
        .isNotSameAs(firstFailure);
    assertThat(((BearerTokenTransportException) secondFailure).getAttempts()).isEqualTo(2);
  }

  @Test
  void getAsync_aMintThatThrowsAtOnce_failsTheCallers() {
    var failure = new IllegalArgumentException("bad form");
    var first = mints.getAsync(KEY, () -> {
      throw failure;
    });

    assertThat(failureOf(first)).isSameAs(failure);
    assertThat(mints.getAsync(KEY, () -> CompletableFuture.completedFuture(entry("t1"))))
        .succeedsWithin(Duration.ofSeconds(1)).isEqualTo(entry("t1"));
  }

  @Test
  void getAsync_aMintThatEndsEmpty_completesEmptyAndCachesNothing() throws Exception {
    var first = mints.getAsync(KEY, () -> CompletableFuture.completedFuture(null));

    assertThat(first.get()).isNull();
    assertThat(cache.getIfPresent(KEY)).isNull();
  }

  @Test
  void getAsync_aTokenCachedMeanwhile_isServedWithoutAMint() throws Exception {
    cache.put(KEY, entry("cached"));

    var first = mints.getAsync(KEY, () -> {
      throw new AssertionError("no mint");
    });

    assertThat(first.get()).isEqualTo(entry("cached"));
  }

  // ---- the failure memory

  @Test
  void memory_aRetryableFailure_isRemembered_untilTheMemoryPasses() {
    var failure = status(503, null);
    assertThatThrownBy(() -> remembering.get(KEY, () -> {
      throw failure;
    })).isSameAs(failure);

    now.addAndGet(MEMORY.toNanos() - 1);
    assertThatThrownBy(() -> remembering.get(KEY, () -> entry("t1")))
        .isInstanceOf(BearerTokenException.class)
        .isNotSameAs(failure)
        .hasMessage(failure.getMessage());

    now.addAndGet(1);
    assertThat(remembering.get(KEY, () -> entry("t1"))).isEqualTo(entry("t1"));
  }

  @Test
  void memory_async_aRememberedFailure_failsWithoutAMint() {
    var minting = new CompletableFuture<TokenEntry>();
    var first = remembering.getAsync(KEY, () -> minting);
    minting.completeExceptionally(transport());
    assertThat(failureOf(first)).isInstanceOf(BearerTokenTransportException.class);

    var second = remembering.getAsync(KEY, () -> {
      throw new AssertionError("no mint");
    });

    assertThat(failureOf(second)).isInstanceOf(BearerTokenTransportException.class);
  }

  @Test
  void memory_unset_remembersNothing() {
    assertThatThrownBy(() -> mints.get(KEY, () -> {
      throw status(503, null);
    })).isInstanceOf(BearerTokenException.class);

    assertThat(mints.get(KEY, () -> entry("t1"))).isEqualTo(entry("t1"));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"400", "401", "403", "404"})
  void memory_aRefusedCredential_isNotRemembered(int status) {
    assertThatThrownBy(() -> remembering.get(KEY, () -> {
      throw status(status, null);
    })).isInstanceOf(BearerTokenException.class);

    assertThat(remembering.get(KEY, () -> entry("t1"))).isEqualTo(entry("t1"));
  }

  @Test
  void memory_anUnusableAnswer_isNotRemembered() {
    assertThatThrownBy(() -> remembering.get(KEY, () -> {
      throw new BearerTokenResponseException(TOKEN_URL, HttpStatus.OK, "access_token",
          Reason.EMPTY_BODY);
    })).isInstanceOf(BearerTokenResponseException.class);

    assertThat(remembering.get(KEY, () -> entry("t1"))).isEqualTo(entry("t1"));
  }

  @Test
  void memory_forget_dropsTheRememberedFailures() {
    assertThatThrownBy(() -> remembering.get(KEY, () -> {
      throw transport();
    })).isInstanceOf(BearerTokenTransportException.class);

    remembering.forget();

    assertThat(remembering.get(KEY, () -> entry("t1"))).isEqualTo(entry("t1"));
  }

  @ParameterizedTest(name = "Retry-After {0}s -> {1}s")
  @CsvSource({"5, 10", "20, 20", "60, 30"})
  void memory_retryAfterLengthensIt_upToMaxRetryAfter(long retryAfter, long expected) {
    assertThat(remembering.memoryFor(MEMORY, status(503, Duration.ofSeconds(retryAfter))))
        .isEqualTo(Duration.ofSeconds(expected));
  }

  @Test
  void memory_withoutRetryAfter_isTheConfiguredOne() {
    assertThat(remembering.memoryFor(MEMORY, transport())).isEqualTo(MEMORY);
    assertThat(remembering.memoryFor(MEMORY, status(503, null))).isEqualTo(MEMORY);
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({"500, true", "501, true", "502, true", "503, true", "504, true", "408, true",
      "429, true", "400, false", "401, false", "403, false", "404, false", "409, false"})
  void isRetryable_byStatus(int status, boolean retryable) {
    assertThat(TokenMints.isRetryable(status(status, null))).isEqualTo(retryable);
  }

  @Test
  void isRetryable_transportYes_othersNo() {
    assertThat(TokenMints.isRetryable(transport())).isTrue();
    assertThat(TokenMints.isRetryable(new IllegalStateException("x"))).isFalse();
    assertThat(TokenMints.isRetryable(new BearerTokenResponseException(TOKEN_URL, HttpStatus.OK,
        "access_token", Reason.EMPTY_BODY))).isFalse();
  }

  // ---- the refusal memory

  @ParameterizedTest(name = "{0}")
  @CsvSource({"400", "401", "403", "404", "409"})
  void refusal_isRemembered_untilItsMemoryPasses(int status) {
    var failure = status(status, null);
    assertThatThrownBy(() -> refusing.get(KEY, () -> {
      throw failure;
    })).isSameAs(failure);

    now.addAndGet(REFUSAL.toNanos() - 1);
    assertThatThrownBy(() -> refusing.get(KEY, () -> {
      throw new AssertionError("no mint");
    })).isInstanceOf(BearerTokenException.class)
        .isNotSameAs(failure)
        .hasMessage(failure.getMessage());

    now.addAndGet(1);
    assertThat(refusing.get(KEY, () -> entry("t1"))).isEqualTo(entry("t1"));
  }

  @Test
  void refusal_async_aRememberedRefusal_failsWithoutAMint() {
    var minting = new CompletableFuture<TokenEntry>();
    var first = refusing.getAsync(KEY, () -> minting);
    minting.completeExceptionally(status(401, null));
    assertThat(failureOf(first)).isInstanceOf(BearerTokenException.class);

    var second = refusing.getAsync(KEY, () -> {
      throw new AssertionError("no mint");
    });

    assertThat(failureOf(second)).isInstanceOf(BearerTokenException.class);
  }

  @Test
  void refusal_aRetryAfterDoesNotLengthenIt() {
    assertThatThrownBy(() -> refusing.get(KEY, () -> {
      throw status(401, Duration.ofSeconds(25));
    })).isInstanceOf(BearerTokenException.class);

    now.addAndGet(REFUSAL.toNanos());

    assertThat(refusing.get(KEY, () -> entry("t1"))).isEqualTo(entry("t1"));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"408", "429", "500", "503"})
  void refusal_aRetryableStatus_isNotARefusal(int status) {
    assertThatThrownBy(() -> refusing.get(KEY, () -> {
      throw status(status, null);
    })).isInstanceOf(BearerTokenException.class);

    assertThat(refusing.get(KEY, () -> entry("t1"))).isEqualTo(entry("t1"));
  }

  @Test
  void refusal_aTransportFailureOrAnUnusableAnswer_isNotARefusal() {
    assertThatThrownBy(() -> refusing.get(KEY, () -> {
      throw transport();
    })).isInstanceOf(BearerTokenTransportException.class);
    assertThatThrownBy(() -> refusing.get(KEY, () -> {
      throw new BearerTokenResponseException(TOKEN_URL, HttpStatus.OK, "access_token",
          Reason.EMPTY_BODY);
    })).isInstanceOf(BearerTokenResponseException.class);

    assertThat(refusing.get(KEY, () -> entry("t1"))).isEqualTo(entry("t1"));
  }

  @Test
  void refusal_ofKeyA_isNeverServedToKeyB() {
    assertThatThrownBy(() -> refusing.get("A", () -> {
      throw status(401, null);
    })).isInstanceOf(BearerTokenException.class);

    assertThat(refusing.get("B", () -> entry("tB"))).isEqualTo(entry("tB"));
    assertThatThrownBy(() -> refusing.get("A", () -> {
      throw new AssertionError("no mint");
    })).isInstanceOf(BearerTokenException.class);
  }

  @Test
  void refusal_andFailure_eachKeepTheirOwnMemory() {
    var both = new TokenMints(cache, MEMORY, Duration.ofSeconds(2), MAX_RETRY_AFTER, now::get);
    assertThatThrownBy(() -> both.get("refused", () -> {
      throw status(401, null);
    })).isInstanceOf(BearerTokenException.class);
    assertThatThrownBy(() -> both.get("down", () -> {
      throw status(503, null);
    })).isInstanceOf(BearerTokenException.class);

    now.addAndGet(Duration.ofSeconds(2).toNanos());

    assertThat(both.get("refused", () -> entry("t1"))).isEqualTo(entry("t1"));
    assertThatThrownBy(() -> both.get("down", () -> {
      throw new AssertionError("no mint");
    })).isInstanceOf(BearerTokenException.class);
  }

  @Test
  void refusal_unset_onlyTheFailureMemory_remembersNoRefusal() {
    var failureOnly = new TokenMints(cache, MEMORY, null, MAX_RETRY_AFTER, now::get);
    assertThatThrownBy(() -> failureOnly.get(KEY, () -> {
      throw status(401, null);
    })).isInstanceOf(BearerTokenException.class);

    assertThat(failureOnly.get(KEY, () -> entry("t1"))).isEqualTo(entry("t1"));
  }

  @Test
  void refusal_forget_dropsTheRememberedRefusals() {
    assertThatThrownBy(() -> refusing.get(KEY, () -> {
      throw status(401, null);
    })).isInstanceOf(BearerTokenException.class);

    refusing.forget();

    assertThat(refusing.get(KEY, () -> entry("t1"))).isEqualTo(entry("t1"));
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({"400, true", "401, true", "403, true", "404, true", "409, true", "408, false",
      "429, false", "500, false", "503, false"})
  void isRefusal_byStatus(int status, boolean refusal) {
    assertThat(TokenMints.isRefusal(status(status, null))).isEqualTo(refusal);
  }

  // ---- a copy per caller

  @Test
  void forCaller_copiesTheTokenFailures() {
    var response = new BearerTokenResponseException(TOKEN_URL, HttpStatus.OK, "access_token",
        Reason.FIELD_MISSING);

    var copy = TokenMints.forCaller(response);

    assertThat(copy).isInstanceOf(BearerTokenResponseException.class).isNotSameAs(response)
        .hasMessage(response.getMessage());
    assertThat(((BearerTokenResponseException) copy).getReason()).isEqualTo(Reason.FIELD_MISSING);
  }

  @Test
  void forCaller_keepsWhatCannotBeRebuilt() {
    var withheldCause = new BearerTokenTransportException(
        URI.create("http://user:secret@idp/token"), 2, new IOException("reset"));
    var noUrl = new BearerTokenException(HttpStatus.SERVICE_UNAVAILABLE);
    var other = new IllegalStateException("x");

    assertThat(TokenMints.forCaller(withheldCause)).isSameAs(withheldCause);
    assertThat(TokenMints.forCaller(noUrl)).isSameAs(noUrl);
    assertThat(TokenMints.forCaller(other)).isSameAs(other);
  }

  @Test
  void defaultMaxRetryAfter_isTheClientDefault() {
    assertThat(new ClientProperties().getMaxRetryAfter())
        .isEqualTo(TokenMints.DEFAULT_MAX_RETRY_AFTER);
  }

  // ---- helpers

  private void awaitInFlight() {
    Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> mints.isInFlight(KEY));
  }

  private static <T> CompletableFuture<T> runAsync(Supplier<T> task) {
    return start(task).future();
  }

  /** Starts the task on its own thread. */
  private static <T> Started<T> start(Supplier<T> task) {
    var future = new CompletableFuture<T>();
    var thread = new Thread(() -> {
      try {
        future.complete(task.get());
      } catch (Throwable t) {
        future.completeExceptionally(t);
      }
    });
    thread.start();
    return new Started<>(future, thread);
  }

  /** A caller that waits for the mint in flight: its thread is parked on that mint. */
  private <T> CompletableFuture<T> joining(Supplier<T> task) {
    var started = start(task);
    Awaitility.await().atMost(Duration.ofSeconds(5))
        .until(() -> started.thread().getState() == Thread.State.WAITING);
    return started.future();
  }

  private record Started<T>(CompletableFuture<T> future, Thread thread) {
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new AssertionError("not released");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static Throwable failureOf(CompletableFuture<?> future) {
    try {
      future.get(5, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      return e.getCause();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    throw new AssertionError("completed without a failure");
  }

  private static BearerTokenException status(int status, Duration retryAfter) {
    var failure = new BearerTokenException(TOKEN_URL, HttpStatusCode.valueOf(status),
        "temporarily_unavailable");
    var headers = new HttpHeaders();
    headers.add("X-Trace", "abc");
    failure.setResponseDetails(headers, retryAfter);
    return failure;
  }

  private static BearerTokenTransportException transport() {
    return new BearerTokenTransportException(TOKEN_URL, 2, new IOException("reset"));
  }

  private static TokenEntry entry(String token) {
    var data = JsonMapper.builder().build().createObjectNode().put("access_token", token);
    return new TokenEntry(data, Duration.ofMinutes(5));
  }
}
