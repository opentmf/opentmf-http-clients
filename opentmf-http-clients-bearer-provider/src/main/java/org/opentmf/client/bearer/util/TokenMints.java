package org.opentmf.client.bearer.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.opentmf.client.bearer.exception.BearerTokenException;
import org.opentmf.client.bearer.exception.BearerTokenResponseException;
import org.opentmf.client.bearer.exception.BearerTokenTransportException;
import org.opentmf.client.bearer.model.TokenEntry;
import org.opentmf.client.common.model.ClientProperties;
import org.springframework.http.HttpStatus;

/**
 * The mints of one token service: at most one mint per token key at a time, and an opt-in short
 * memory of a mint that failed retryably.
 *
 * <p><b>One mint per key.</b> A caller that finds no cached token while a mint of its key is in
 * flight does not mint again: it waits for that mint and shares its outcome, the token or the
 * failure. The mint runs on the thread of the caller that started it (sync), or subscribed with
 * that caller's Reactor context (reactive), so its call, its log lines and its trace belong to
 * that caller. A reactive caller that cancels stops waiting; the mint finishes and its token is
 * cached for the next caller. A failure is shared only by the callers waiting for that mint: once
 * it settles, the next caller mints again, unless the failure memory is on.</p>
 *
 * <p><b>The failure memory</b> ({@code bearer-auth.failure-memory}, off when unset). A mint that
 * failed retryably (a 5xx, 408 or 429 from the token endpoint, or a transport failure) is
 * remembered for that long, or for the endpoint's {@code Retry-After} when it asks for longer, up
 * to {@code max-retry-after}: until then every caller of the key fails at once with that failure,
 * without a call. A refused credential (any other 4xx) and an unusable answer are never
 * remembered. {@link #forget()} drops every remembered failure.</p>
 *
 * <p>Every caller but the one that ran the mint gets its own copy of a token failure, of the same
 * type and with the same message, status, headers and cause, so no exception instance is shared
 * between callers.</p>
 */
@Slf4j
public final class TokenMints {

  /** The default {@code max-retry-after} of a client. */
  public static final Duration DEFAULT_MAX_RETRY_AFTER = new ClientProperties().getMaxRetryAfter();

  private final Cache<String, TokenEntry> cache;
  private final @Nullable Duration failureMemory;
  private final Duration maxRetryAfter;
  private final Ticker ticker;
  private final Map<String, CompletableFuture<TokenEntry>> inFlight = new ConcurrentHashMap<>();
  private final Map<String, Remembered> failures = new ConcurrentHashMap<>();

  /**
   * @param cache         the service's token cache: a mint's token is put there before the
   *                      callers waiting for it are released
   * @param failureMemory how long a retryable failure is remembered, or {@code null} for never
   * @param maxRetryAfter the longest {@code Retry-After} that lengthens the memory
   */
  public TokenMints(Cache<String, TokenEntry> cache, @Nullable Duration failureMemory,
      Duration maxRetryAfter) {
    this(cache, failureMemory, maxRetryAfter, Ticker.systemTicker());
  }

  /** As above, with the clock of the failure memory. */
  public TokenMints(Cache<String, TokenEntry> cache, @Nullable Duration failureMemory,
      Duration maxRetryAfter, Ticker ticker) {
    this.cache = cache;
    this.failureMemory = failureMemory;
    this.maxRetryAfter = maxRetryAfter;
    this.ticker = ticker;
  }

  /**
   * The token of the key from the mint in flight, or from a new mint run on this thread; a
   * remembered failure without a call.
   */
  public TokenEntry get(String key, Supplier<TokenEntry> mint) {
    var mine = new CompletableFuture<TokenEntry>();
    var running = inFlight.putIfAbsent(key, mine);
    if (running != null) {
      return await(running);
    }
    try {
      var entry = settledMeanwhile(key);
      if (entry == null) {
        entry = mintAndCache(key, mint);
      }
      mine.complete(entry);
      return entry;
    } catch (RuntimeException e) {
      mine.completeExceptionally(e);
      throw e;
    } finally {
      inFlight.remove(key, mine);
      // an Error: the callers waiting for this mint fail instead of waiting for ever
      mine.cancel(false);
    }
  }

  /**
   * The token of the key from the mint in flight, or from a new mint started on this thread by
   * {@code mint}; a remembered failure without a call. The future is this caller's own: its
   * failure is this caller's copy, and cancelling it cancels neither the mint nor another
   * caller's wait.
   */
  public CompletableFuture<TokenEntry> getAsync(String key,
      Supplier<CompletableFuture<TokenEntry>> mint) {
    var mine = new CompletableFuture<TokenEntry>();
    var running = inFlight.putIfAbsent(key, mine);
    if (running == null) {
      running = mine;
      start(key, mine, mint);
    }
    var callers = new CompletableFuture<TokenEntry>();
    running.whenComplete((entry, failure) -> {
      if (failure == null) {
        callers.complete(entry);
      } else {
        callers.completeExceptionally(forCaller(unwrap(failure)));
      }
    });
    return callers;
  }

  /** Whether a mint of the key is in flight. */
  boolean isInFlight(String key) {
    return inFlight.containsKey(key);
  }

  /** Drops every remembered failure, so the next caller of each key mints. */
  public void forget() {
    failures.clear();
  }

  private void start(String key, CompletableFuture<TokenEntry> mine,
      Supplier<CompletableFuture<TokenEntry>> mint) {
    TokenEntry settled;
    try {
      settled = settledMeanwhile(key);
    } catch (RuntimeException remembered) {
      settle(key, mine, null, remembered);
      return;
    }
    if (settled != null) {
      settle(key, mine, settled, null);
      return;
    }
    CompletableFuture<TokenEntry> minting;
    try {
      minting = mint.get();
    } catch (RuntimeException e) {
      minting = CompletableFuture.failedFuture(e);
    }
    minting.whenComplete((entry, failure) -> {
      if (failure == null) {
        if (entry != null) {
          cache.put(key, entry);
        }
      } else {
        remember(key, unwrap(failure));
      }
      settle(key, mine, entry, failure == null ? null : unwrap(failure));
    });
  }

  private static Throwable unwrap(Throwable failure) {
    return failure instanceof CompletionException && failure.getCause() != null
        ? failure.getCause()
        : failure;
  }

  private void settle(String key, CompletableFuture<TokenEntry> mine, @Nullable TokenEntry entry,
      @Nullable Throwable failure) {
    if (failure == null) {
      mine.complete(entry);
    } else {
      mine.completeExceptionally(failure);
    }
    inFlight.remove(key, mine);
  }

  private TokenEntry mintAndCache(String key, Supplier<TokenEntry> mint) {
    TokenEntry entry;
    try {
      entry = mint.get();
    } catch (RuntimeException e) {
      remember(key, e);
      throw e;
    }
    cache.put(key, entry);
    return entry;
  }

  /**
   * What the claim of a key finds before it mints: a token cached meanwhile, or the key's
   * remembered failure, thrown; {@code null} when the claim must mint.
   */
  private @Nullable TokenEntry settledMeanwhile(String key) {
    var cached = cache.getIfPresent(key);
    if (cached != null) {
      return cached;
    }
    throwIfRemembered(key);
    return null;
  }

  private static TokenEntry await(CompletableFuture<TokenEntry> running) {
    try {
      return running.get();
    } catch (ExecutionException e) {
      throw asRuntime(forCaller(e.getCause()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for the bearer token mint in"
          + " flight", e);
    }
  }

  private static RuntimeException asRuntime(Throwable failure) {
    if (failure instanceof RuntimeException e) {
      return e;
    }
    if (failure instanceof Error e) {
      throw e;
    }
    return new IllegalStateException("Bearer token mint failed", failure);
  }

  private void throwIfRemembered(String key) {
    var remembered = remembered(key);
    if (remembered != null) {
      throw remembered;
    }
  }

  /** A copy of the remembered failure of the key while it lasts, else {@code null}. */
  private @Nullable RuntimeException remembered(String key) {
    var remembered = failures.get(key);
    if (remembered == null) {
      return null;
    }
    if (ticker.read() - remembered.untilNanos() >= 0) {
      failures.remove(key, remembered);
      return null;
    }
    log.debug("Failing a bearer token request at once: the last mint failed less than"
        + " bearer-auth.failure-memory ago ({})", remembered.failure().getMessage());
    return asRuntime(forCaller(remembered.failure()));
  }

  private void remember(String key, Throwable failure) {
    var configured = failureMemory;
    if (configured == null || !(failure instanceof RuntimeException runtime)
        || !isRetryable(runtime)) {
      return;
    }
    var memory = memoryFor(configured, runtime);
    failures.put(key, new Remembered(runtime, ticker.read() + memory.toNanos()));
    log.warn("Bearer token mint failed ({}); every token request of this key fails at once for"
        + " {} ms (bearer-auth.failure-memory)", runtime.getMessage(), memory.toMillis());
  }

  /**
   * The configured memory, or the endpoint's {@code Retry-After} when it asks for longer, up to
   * max-retry-after.
   */
  Duration memoryFor(Duration configured, RuntimeException failure) {
    var retryAfter = failure instanceof BearerTokenException e ? e.getRetryAfter() : null;
    if (retryAfter == null) {
      return configured;
    }
    var asked = retryAfter.compareTo(maxRetryAfter) > 0 ? maxRetryAfter : retryAfter;
    return asked.compareTo(configured) > 0 ? asked : configured;
  }

  /** A transport failure, or a 5xx, 408 or 429 from the token endpoint. */
  static boolean isRetryable(Throwable failure) {
    if (failure instanceof BearerTokenTransportException) {
      return true;
    }
    if (failure instanceof BearerTokenException e) {
      var status = e.getStatusCode();
      return status.is5xxServerError()
          || status.value() == HttpStatus.REQUEST_TIMEOUT.value()
          || status.value() == HttpStatus.TOO_MANY_REQUESTS.value();
    }
    return false;
  }

  /**
   * A copy of a token failure for one caller, with its own stack trace; any other failure as it
   * is. A transport failure whose cause was withheld (a token URL with a user part, a query or a
   * fragment) cannot be rebuilt and is returned as it is.
   */
  static Throwable forCaller(Throwable failure) {
    if (failure instanceof BearerTokenTransportException e && e.getCause() != null) {
      return new BearerTokenTransportException(e.getTokenUrl(), e.getAttempts(), e.getCause());
    }
    if (failure instanceof BearerTokenException e && e.getTokenUrl() != null) {
      var copy = new BearerTokenException(e.getTokenUrl(), e.getStatusCode(), e.getOauthError());
      copy.setResponseDetails(e.getHeaders(), e.getRetryAfter());
      return copy;
    }
    if (failure instanceof BearerTokenResponseException e) {
      return new BearerTokenResponseException(e.getTokenUrl(), e.getStatusCode(),
          e.getTokenField(), e.getReason());
    }
    return failure;
  }

  private record Remembered(RuntimeException failure, long untilNanos) {
  }
}
