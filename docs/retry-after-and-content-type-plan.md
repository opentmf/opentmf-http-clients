# Implementation plan — `Retry-After`, `Content-Type`, error-path headers, single retry authority

**Status:** plan (2026-08-05), for `2.1.6`. Follows the analysis in
[`http-response-headers.md`](http-response-headers.md) and the decisions taken
after it.

**Decided scope.** Act on exactly two response headers — `Retry-After` and
`Content-Type` — capture headers on the **error path only**, and consolidate
retry policy into one place by disabling Apache's transport-level retries.
Explicitly **out of scope**: any response cache, any success-path header
exposure, and every other header in the analysis document.

---

## 1. Two facts this plan is built on

**1.1 Apache already honours `Retry-After` today.**
`ApacheRestTemplateFactory` builds with `HttpClients.custom()` and never calls
`disableAutomaticRetries()`, so it inherits
`DefaultHttpRequestRetryStrategy.INSTANCE`. In httpclient5 5.6.1 that is
`new DefaultHttpRequestRetryStrategy()` → `this(1, TimeValue.ofSeconds(1L))`:
**1 automatic retry, 1s default interval, on 429 and 503, idempotent requests
only**, and `getRetryInterval(...)` parses `Retry-After` (both `delay-seconds`
and HTTP-date). This is visible in our own IT logs
(`HttpRequestRetryExec -- ... responded with status 503; request will be
automatically re-executed in 1 SECONDS (exec count 2)`).

Consequence: the *feature gap* is JDK and Netty, not Apache. The *problem* is
that Apache silently applies a second, uncontrollable retry policy on top of
whatever the caller asked for.

**1.2 The library never retries the caller's requests.**
`SyncClientUtil.executeWithRetry(...)` and `WebClientUtil.retry(...)` are
opt-in utilities the *application* invokes with its own parameters. The only
production consumer of `num-retries` / `retry-wait-duration` is
`BearerTokenClientImpl` (reactive token fetch). `ClientProperties` already
documents this accurately:

> *"Used internally only for bearer-token retrieval; regular HTTP calls are
> never retried automatically — pass this value to
> `SyncClientUtil.executeWithRetry(...)` or `WebClientUtil.retry(...)` to opt
> in at the call site."*

Consequence: there is far less "our configuration" for `Retry-After` to
conflict with than it first appears.

## 2. The reconciliation rule

**The server controls *when*; we control *how many* and *at most how long*.**

| Concern | Authority | Rule |
|---|---|---|
| Number of attempts | **Ours** | `Retry-After` never changes `maxAttempts`. It says when to come back, not how often to try. |
| Delay between attempts | **Server, floored** | `delay = max(computedBackoff, retryAfter)` — the server may only ask us to be *more* patient than our own backoff, never less. |
| Upper bound | **Ours, absolute** | If `retryAfter > max-retry-after`, do **not** wait and do **not** retry — fail immediately with the requested delay in the message. |

Floor rather than replace semantics matters: under replace semantics a server
returning `Retry-After: 0` could pull us into a tighter retry loop than our own
configuration permits. Flooring removes that lever entirely.

Fail-fast beyond the cap rather than clamping: if the origin says "not for an
hour", retrying at the cap anyway just earns another 429 and hammers a server
that explicitly asked for room. Converting it to a fast, diagnosable failure is
both kinder and more useful.

## 3. Work items

### 3.1 Single retry authority — disable Apache automatic retries

`ApacheRestTemplateFactory.create(...)`, on the existing `httpClientBuilder`
chain (around line 80):

```java
HttpClientBuilder httpClientBuilder = HttpClients.custom()
    .setConnectionManager(connManager)
    .setDefaultRequestConfig(requestConfig)
    // Retry policy belongs to SyncClientUtil.executeWithRetry / WebClientUtil.retry,
    // in one place, for all three backends. Apache's DefaultHttpRequestRetryStrategy
    // would otherwise add an invisible second retry (429/503, honouring Retry-After)
    // on top of whatever the caller configured, multiplying total attempts.
    .disableAutomaticRetries()
    .evictIdleConnections(...);
```

**This is a behaviour change for existing consumers** and needs a CHANGELOG
entry saying so plainly: Apache-backed clients previously retried 429/503 once
automatically; they no longer do, and callers wanting retries must opt in at
the call site like the other two backends. Today the same `num-retries: 3`
yields up to `4 × 2 = 8` attempts on Apache and 4 on JDK/Netty — that
inconsistency is what this removes.

### 3.2 Capture error-path headers + parsed `Retry-After`

`OpenTmfClientResponseException` (common) gains two fields:

```java
private final @Nullable HttpHeaders headers;      // read-only copy, may be null
private final @Nullable Duration retryAfter;      // parsed, un-capped
```

**Constraint — additive only.** `HttpClientUtil` resolves constructors
*reflectively by exact signature*: `createException` uses
`(HttpStatusCode)` and `(HttpStatusCode, String)`, and `remap` tries
`(HttpStatusCode, String, String)` then falls back to `(HttpStatusCode,
String)` on `NoSuchMethodException`. All four existing constructors must remain
untouched, and every subclass (`OpenTmfClientNotFoundException`, plus consumer
subclasses in other repos) must keep those arities. New state therefore arrives
via an additional constructor or a package-private setter used by the handlers
— **not** by changing the existing ones.

Also: store `HttpHeaders.readOnlyHttpHeaders(...)` copies — the live
`ClientHttpResponse` is closed immediately after the handler runs. Bump
`serialVersionUID` to `5L` and add a serialization round-trip test.

`remap` should carry headers and `retryAfter` across to the remapped instance,
or the information silently vanishes for consumers using domain exceptions.

### 3.3 New shared parsing helper

`HttpClientUtil` (common) — one place, used by all three handlers:

```java
public static @Nullable Duration parseRetryAfter(@Nullable String headerValue)
```

- `delay-seconds` form: `Long.parseLong`, negative → `null`.
- HTTP-date form: `ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME)`,
  delta against now; **negative or absurd deltas clamp to `null`**, not to a
  huge value (clock skew tolerance).
- Unparseable → `null`, no exception, no log noise above debug.

### 3.4 Wire the three error handlers

Identical three-line change in each — all already hold the open response:

| File | Change |
|---|---|
| `OpenTmfResponseErrorHandler.handleError` (rest) | read `response.getHeaders()`, parse `Retry-After`, pass both to the exception; pass `Content-Type` to `ErrorBodyExtractor` |
| `OpenTmfRestClientStatusHandler.handleError` (rest) | same |
| `WebClientConfigUtil.errorWrappingFilter` (reactive) | same, via `response.headers().asHttpHeaders()` |

Note `SyncClientUtil.handleError` and `WebClientUtil.handleError` are the
*manual/legacy* variants operating on already-thrown framework exceptions —
they cannot see headers and are left unchanged.

### 3.5 `Content-Type` in `ErrorBodyExtractor`

New overloads, existing ones retained and delegating (they are public API):

```java
public static String extractMessage(HttpStatusCode status, byte[] body,
    @Nullable MediaType contentType)
public static String decodeAsText(byte[] body, @Nullable Charset charset)
```

- Charset from `contentType.getCharset()`, defaulting to UTF-8 → fixes
  non-UTF-8 vendors that today's fixed-UTF-8 decoder mangles.
- Content type only *informs* the parse order. **Keep the existing trial-parse
  fallback** — vendors mislabel bodies routinely (JSON under `text/html`, HTML
  error pages from proxies under `application/json`). Removing the fallback
  would be a regression.
- Optionally recognise `application/problem+json` explicitly; the existing
  `MESSAGE_FIELDS` list already covers RFC 9457 fields, so this is
  presentation only and can be skipped.

### 3.6 Sync retry — `SyncClientUtil.executeWithRetry`

```java
private static final Duration DEFAULT_MAX_RETRY_AFTER = Duration.ofSeconds(60);
```

Inside the existing catch block, replacing the current `sleepMs` computation:

1. `long backoffMs = baseMs * (1L << Math.min(attempt, 20));` — **also fixes
   the pre-existing uncapped shift**, which is undefined at `attempt >= 63`.
2. `long jittered = applyJitter(backoffMs, jitterFactor);`
3. `Duration ra = retryAfterOf(e);` (null unless the exception carries one)
4. if `ra != null && ra > maxRetryAfter` → **rethrow immediately**, message
   naming the requested delay and the cap.
5. `long sleepMs = ra == null ? jittered : Math.max(jittered, ra.toMillis());`

New overloads taking `Duration maxRetryAfter`; existing signatures delegate
with `DEFAULT_MAX_RETRY_AFTER`, so **no caller changes and no API break**.

### 3.7 Reactive retry — `WebClientUtil.retry` (revised approach)

The obvious route is replacing `Retry.backoff` with
`Retry.from(companion -> ...)` for a per-signal delay. **Recommend against it**:
it forces reimplementing exponential backoff, jitter, attempt counting and
`onRetryExhaustedThrow` by hand, and it changes the public return type from
`RetryBackoffSpec` to `Retry`, breaking source compatibility for anyone who
declared the variable.

Use `doBeforeRetryAsync` instead — it composes an *additional* wait on top of
the spec's own backoff, keeping every existing semantic and the return type:

```java
return Retry.backoff(maxAttempts, duration)
    .jitter(jitterFactor)
    .filter(WebClientUtil::shouldRetryOn)
    .doBeforeRetryAsync(signal -> {
      Duration ra = retryAfterOf(signal.failure());
      if (ra == null) {
        return Mono.empty();
      }
      if (ra.compareTo(maxRetryAfter) > 0) {
        return Mono.error(/* fail fast, as in 3.6 step 4 */);
      }
      return Mono.delay(ra).then();
    })
    .doAfterRetry(...)                       // unchanged
    .onRetryExhaustedThrow(...);             // unchanged
```

**Known semantic difference, accepted deliberately:** this yields
`backoff + retryAfter` rather than `max(backoff, retryAfter)`. It always waits
at least as long as the floor rule requires, so it is never more aggressive —
only slightly more patient (base 5s + `Retry-After: 2s` → 7s instead of 5s).
That over-wait is benign for a client honouring a throttle, and it buys a
~10-line change with zero API break instead of a hand-rolled retry loop. If
exact floor semantics is ever required, `Retry.from` remains the fallback.

### 3.8 Configuration

`ClientProperties`, immediately after `retryWaitDuration`:

```java
/**
 * Longest server-requested {@code Retry-After} delay this client will honour. A retryable
 * response asking for longer fails immediately instead of waiting, turning an unbounded
 * stall into a fast, diagnosable error. Applies only when retries are opted into at the
 * call site (see {@code numRetries}). Default 60s.
 */
private Duration maxRetryAfter = Duration.ofSeconds(60);
```

Plus: `ClientPropertiesValidator.validateConnectionSettings` gains
`requirePositive(properties.getMaxRetryAfter(), "max-retry-after", findings)`;
`BearerTokenClientImpl` passes `properties.getMaxRetryAfter()` into
`WebClientUtil.retry(...)`, so a throttled IdP is respected.

## 4. Tests

| Area | Cases |
|---|---|
| `HttpClientUtil.parseRetryAfter` | seconds; RFC 1123 date; past date → null; negative → null; garbage → null; null input |
| `ErrorBodyExtractor` | charset from `Content-Type`; mislabelled JSON-as-`text/html` still parsed (fallback intact); missing/blank content type = today's behaviour |
| `OpenTmfClientResponseException` | header copy is read-only; serialization round-trip; `remap` preserves headers + `retryAfter`; all four legacy constructors still reflectively resolvable |
| Error handlers (×3) | headers reach the exception; `Retry-After` parsed; 404 → `OpenTmfClientNotFoundException` unchanged |
| `SyncClientUtil` | floor applied; cap → immediate rethrow, no sleep; no header = today's backoff exactly; `attempt >= 63` no longer overflows |
| `WebClientUtil` | extra delay applied; over-cap fails fast; unchanged when header absent; `RetryBackoffSpec` still the return type |
| Apache | IT asserting a 429/503 is **no longer** transparently retried by the transport (invert the existing expectation) |
| End-to-end | mockserver returning `503` + `Retry-After: 2`, asserting elapsed time on both sync and reactive paths |

Coverage must stay ≥ 80% per module (JaCoCo `BUNDLE` check), so every new
branch needs a case — particularly the fail-fast and null-header paths.

## 5. Documentation

- **CHANGELOG** — new `## [2.1.6]` section (pom is `2.1.6-SNAPSHOT`; bare
  numeric heading). `### Changed` for the Apache retry removal, stated as a
  behaviour change. `### Added` for `Retry-After` honouring, error-path
  headers, `max-retry-after`, `Content-Type`-aware error parsing.
- **README** — `max-retry-after` in the property table; a short "Retry
  behaviour" note stating that retries are opt-in at the call site on **all
  three** backends, and that a server's `Retry-After` can lengthen but never
  shorten the wait, bounded by `max-retry-after`.
- **`docs/http-response-headers.md`** — add a status line recording the
  decisions: act on `Retry-After` + `Content-Type`, error-path capture only,
  no cache, no success-path exposure.

## 6. Suggested order

1. §3.2 exception state + §3.3 parser (foundation, no behaviour change)
2. §3.4 handlers + §3.5 `Content-Type` (behaviour-preserving)
3. §3.6 sync + §3.8 config
4. §3.7 reactive
5. §3.1 Apache disable — last, so the ITs that change expectation land after
   the replacement policy exists
6. Docs, then `mvn -Psonar clean verify` with gate check

## 7. Decisions still open

1. **Cap default** — 60s proposed. 30s is safer; "never exceed
   `request-timeout`" is self-consistent but often surprisingly short.
2. **Escape hatch for Apache retries** — none proposed (that is the point of
   one authority). Add a property if any consumer is known to depend on the
   old behaviour.
3. **`Retry-After` on non-retryable statuses** — proposal is to honour it only
   where `isRetryableStatus` already returns true, so it can never *introduce*
   a retry, only reschedule one.
4. **Additive vs. floor delay on the reactive path** (§3.7) — additive is
   recommended; confirm the slight over-wait is acceptable.
