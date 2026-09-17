# Token client: closed response stream on a bearer-token mint

**Status:** plan · **Target:** 2.2.0 (see §6 for why MINOR) · **Origin:** e2e finding row 13,
`test-reports/2026-09-17-cezmi-FINDINGS-rows-13-15.md` (dnotify analysis repo), traceId
`7125661c0e3cbed91ce5f3f34aa66b9a`, 2026-09-16T09:45:15.301Z.

Per repo convention this document is deleted once implemented; the durable facts move to the
README (token-fetch section) and the CHANGELOG. Git history keeps the plan.

## 1. Problem

Once in a gate pod's 12-hour life, a bearer-token mint through this library's sync token client
failed and the adopter (dnms-gate, via dnms-catalog-client's `AuthorizingTransport.authorize`)
answered an unexplained 500 instead of the contract's 404:

```
java.io.IOException: closed
  at jdk.internal.net.http.ResponseSubscribers$HttpResponseInputStream.current/read
  at org.springframework.web.client.IntrospectingClientHttpResponse.hasEmptyMessageBody(:93)
Wrapped by: RestClientException: Error while extracting response for type [ObjectNode]
  at org.opentmf.client.bearer.sync.SyncTokenClientImpl.getToken(SyncTokenClientImpl.java:35)
  at org.opentmf.client.bearer.sync.SyncBearerTokenServiceImpl.getTokenInternal(:59)
```

The next call succeeded. A 40-call rate test cannot reproduce it: the token is cached, so those
40 calls exercise the mint path at most once. Three asks came with the finding: (1) never read a
response after it is consumed/closed and retry an idempotent mint once on a closed keep-alive;
(2) map the surviving failure to a 502/503 problem document in the adopter (separate row, needs
a typed exception from us); (3) make mints observable so a rate can be stated next time.

## 2. Measured facts (read from the sources on this machine)

Versions: spring-web 7.0.8 (Spring Boot 4.1.0), Logbook 4.1.0, JDK 17 `java.net.http`.

1. **What "closed" means.** `HttpResponseInputStream.current()` throws
   `new IOException("closed", failed)` when `closed || failed != null`. Same message for two
   different situations: the stream was closed locally, or the body subscription received
   `onError(t)` (`failed = t`). The cause chain — absent from the finding's excerpt — tells them
   apart. e2e should attach the full `Caused by` chain on the next recurrence.
2. **Nothing on our chain closes the stream before it is read.** Audit of the sync path:
   `SyncTokenClientImpl` reads the body exactly once (`retrieve().body(ObjectNode.class)`).
   Spring's `DefaultRestClient.readWithMessageConverters` closes the response in a
   try-with-resources *after* extraction; `IntrospectingClientHttpResponse.hasEmptyMessageBody`
   peeks one byte and `reset()`s/`unread()`s it (a legitimate peek, not a second read). Logbook's
   `BufferingClientHttpResponseWrapper` wraps the JDK stream in a `BufferedInputStream` and its
   `RemoteResponse.buffer()` uses `mark(MAX)`/`reset()`; both read before, never after, close.
   Our `GzipClientHttpResponse` creates its `GZIPInputStream` once and caches it. The reactive
   twin (`BearerTokenClientImpl`) is a single `bodyToMono(String.class)`. **Conclusion: the
   failure was `failed != null` — the body subscription errored after the status line and headers
   had already been parsed.** The token endpoint had started answering.
3. **Line 93 confirms Logbook was on the chain.** `hasEmptyMessageBody():93` is `body.read()`
   inside the `markSupported()` branch, which only runs when the body stream supports mark — the
   raw JDK stream does not, Logbook's `BufferedInputStream` does. This is also why the excerpt
   shows the JDK frame directly under the Spring frame: the `BufferedInputStream.fill()` frames
   were elided. It is consistent with `logging-enabled: true` on the gate's catalog client while
   Logbook's own level/condition kept the token exchange out of the log (which is why
   `openid-connect/token` never appears in 12 hours of gate log).
4. **The JDK HttpClient's built-in stale-connection retry does not cover a token mint.**
   `MultiExchange.canRetryRequest()` retries a request whose pooled connection turned out dead
   (`ConnectionExpiredException`) only for `GET`/`HEAD`, unless the JVM-global
   `-Djdk.httpclient.enableAllMethodRetry=true` is set. The token fetch is a `POST`
   (`application/x-www-form-urlencoded`), so *any* stale keep-alive connection reaches the caller
   as an exception: at the headers stage as `ResourceAccessException` (cause `IOException`,
   e.g. "EOF reached while reading"), at the body stage as fact 1's `IOException("closed", …)`
   wrapped in `RestClientException`. Apache-backed clients are in the same position since 2.1.6
   disabled Apache's automatic retries (`NoHttpResponseException`).
5. **Keep-alive interplay.** JDK HTTP/1.1 pooled connections idle for `jdk.httpclient.keepalive.timeout`
   (default **1200 s**) before the client drops them; Keycloak on Quarkus idles connections after
   `quarkus.http.idle-timeout` (default 30 min), but intermediate hops (ingress-nginx
   `upstream-keepalive-timeout` 60 s, Envoy 1 h, kube-proxy conntrack) commonly close earlier. Every
   window in which the peer's idle timer is shorter than the client's is a race between the
   client's reuse and the peer's FIN. With HTTP/2 (JDK default, negotiated over TLS) the same race
   surfaces as a `GOAWAY`/`RST_STREAM` on the in-flight stream — exactly a body-stage `onError`
   (fact 2). The JDK discards a connection whose read failed (`Http1Response`: "don't return the
   connection to the cache if EOF happened"), and Apache's pool does the same, so a second attempt
   lands on a different connection **by construction** — no pool-eviction API is needed.
6. **The library's sync token path has no retry at all today** — not on statuses either. The README
   ("the only internal retry is on bearer token retrieval … using `num-retries`") describes the
   reactive twin only. Corrected in this change (docs), status-retry parity is a separate item.
7. **Existing exception model.** `BearerTokenException extends OpenTmfClientResponseException`
   (status-bearing; the reactive twin maps IdP error statuses to it, the sync twin lets the
   RestClient status handler's `OpenTmfClientResponseException` through). Nothing typed exists for
   "no usable HTTP response at all" — the adopter's catch-all sees a bare `RestClientException`.
8. **Existing meters use the `opentmf.client.*` namespace** (`opentmf.client.pool.leased|…{client}`),
   registered from the autoconfigure module against an optional `MeterRegistry` bean, with the
   proven rule that optional-dependency types never appear in a bean class's method signatures
   (see `HttpClientRegistryOptionalDepsTests`).

## 3. Design

### 3.1 Retry once on a transport-level failure (both twins)

The token fetch is idempotent (`client_credentials`/`password` grants mint a fresh token; nothing
is consumed server-side). On a **transport-level** failure the client retries **exactly once**,
immediately, then fails.

*Transport-level* := the throwable is **not** a status-bearing or resilience exception
(`OpenTmfClientResponseException`, `RestClientResponseException`, `WebClientResponseException`,
`OpenTmfClientResilienceException` — an open breaker means *stop calling*), **and** either it is a
`WebClientRequestException` or an `IOException` appears in its cause chain. This covers, in one
predicate: stale keep-alive at the headers stage (`ResourceAccessException`), body-stage
`IOException("closed", …)` behind Spring's extractor wrapper, connection reset, premature EOF,
`PrematureCloseException`, and I/O timeouts (`HttpTimeoutException` is an `IOException`; a retried
timeout doubles the worst-case mint latency to 2 × `response-timeout` — accepted, a mint is rare
and idempotent). Malformed JSON is *not* transport (Jackson 3's `JacksonException` is unchecked and
has no `IOException` cause) and is not retried.

- Sync: `SyncTokenClientImpl.getToken` — first attempt; on transport failure WARN
  `Bearer token fetch from <url> failed at the transport level (<cause class>: <message>); retrying once`
  and attempt again. Status errors pass through unchanged on either attempt.
- Reactive: `BearerTokenClientImpl.post` — `retryWhen(Retry.max(1).filter(transport))` **inside**
  the existing status-based `retryWhen`, so each status-retry attempt owns its own single
  transport retry. The reactive twin cannot double-read either; the change is for symmetry.

No configuration property. One retry is the whole policy; making it tunable would only invite
"retry until it works" on a path whose failures are, by fact 5, single-connection races.

### 3.2 Typed failure for the adopter's mapper

New `org.opentmf.client.bearer.exception.BearerTokenTransportException extends RuntimeException`
— thrown when the *second* attempt also fails at the transport level. Carries `tokenUrl` (URI, no
secrets) and `attempts` (2); cause = the last failure. Deliberately **not** an
`OpenTmfClientResponseException`: no HTTP status exists to report, and the retry utilities must
never retry it further (same reasoning as `OpenTmfClientResilienceException`). Adopters map it to
503 (identity provider unreachable); a status-bearing `OpenTmfClientResponseException` /
`BearerTokenException` from the token endpoint stays what it is (adopter maps to 502). The template
row that adds the mapping is separate.

### 3.3 Observability

- **Counter** `opentmf.client.token.fetch` (Prometheus `opentmf_client_token_fetch_total`),
  tags `client=<clientId>`, `outcome=ok|retried|failed`. One increment per mint: `ok` first
  attempt succeeded, `retried` second attempt succeeded, `failed` the mint threw (any cause).
  Named in the existing `opentmf.client.*` namespace rather than the brief's
  `opentmf_http_clients_token_fetch_total`, for consistency with the pool gauges.
- **One INFO line per mint** on success:
  `Bearer token minted from <tokenUrl> (scope: <scope>, attempt <n>/2, <ms> ms)` — the URL names
  the issuer host and realm; form data (secrets) never appears. Today's debug line stays.
- Wiring: a tiny `TokenFetchListener` interface in bearer-provider
  (`onTokenFetch(URI tokenUrl, Outcome outcome)`, `Outcome {OK, RETRIED, FAILED}`, `noop()`), no
  Micrometer types. The autoconfigure module adds `TokenFetchMeters` (holds the `MeterRegistry`,
  `listenerFor(clientId)` returns a listener that increments the counter), exposed by a
  `@ConditionalOnClass(MeterRegistry)` + `@ConditionalOnBean(MeterRegistry)` configuration —
  the `ApachePoolMeters` pattern. Both registrars take `ObjectProvider<TokenFetchMeters>` and
  pass the listener when building the token clients (static and `HttpClientRegistry` dynamic
  clients alike). Without a `MeterRegistry` bean: `noop()`, zero behaviour change.

### 3.4 Double-read guard

The audit (fact 2) found no double read; the guard is a test that keeps it that way: a unit test
drives `SyncTokenClientImpl` through the library's real interceptor chain (gzip + a Logbook
interceptor that buffers bodies) over a stub `ClientHttpRequestFactory` whose response stream
behaves like the JDK's (throws `IOException("closed")` on any read after `close()`) and counts
reads-after-close. Assertion: token parsed, zero reads after close, stream closed once.

## 4. Public API delta

| Item | Kind |
|---|---|
| `org.opentmf.client.bearer.exception.BearerTokenTransportException` | new class |
| `org.opentmf.client.bearer.observe.TokenFetchListener` (+ `Outcome`) | new interface |
| `SyncTokenClientImpl(RestClient, BearerAuthConfig, TokenFetchListener)` | new ctor; 2-arg stays (noop listener) |
| `BearerTokenClientImpl(ClientProperties, BearerAuthConfig, WebClient, TokenFetchListener)` | new ctor; 3-arg stays |
| `RestClientRegistrar.createTokenService(String clientId, RestClient, ClientProperties)` | new overload; 2-arg stays (no metrics) |
| `org.opentmf.client.starter.TokenFetchMeters` + configuration | new (autoconfigure) |
| meter `opentmf.client.token.fetch{client,outcome}` | new |

Behaviour delta for existing adopters: a transport failure on a mint now costs one extra attempt
before failing, and the failure type after two attempts is `BearerTokenTransportException` instead
of `RestClientException`/`ResourceAccessException` (sync) or the raw reactor error (reactive).
Status errors are untouched.

## 5. Tests (red first)

bearer-provider (MockServer, JDK HttpClient via `RestClient`):
1. `SyncTokenClientTransportRetryIT`
   - **body-stage drop** — `error().withResponseBytes("HTTP/1.1 200 OK\r\nContent-Length: 200\r\nContent-Type: application/json\r\n\r\n").withDropConnection(true)` once, then the normal JWKS token expectation → this reproduces fact 1 exactly (`IOException: closed` under `hasEmptyMessageBody`); assert token returned, listener saw `RETRIED`, one WARN.
   - **headers-stage drop** — `error().withDropConnection(true)` once → same outcome via `ResourceAccessException`.
   - **two drops** → `BearerTokenTransportException` with `attempts == 2`, cause chain contains `IOException`, listener saw `FAILED`.
   - **status error** (401 from the stub) → thrown unchanged, exactly one request received by the stub, listener saw `FAILED`.
   - **ok** → listener saw `OK`, one request.
2. `BearerTokenClientTransportRetryIT` — the same four cases on the reactive twin.
3. `TokenResponseStreamGuardTest` — §3.4.
4. `TransportFailuresTest` — the predicate table (IOException-chained yes; response/resilience
   exceptions no; unchecked JSON error no).

autoconfigure:
5. `TokenFetchMetersTest` — `SimpleMeterRegistry`: three outcomes increment three series with
   the `client` tag; no bean → registrars hand out `noop()` (context test without `MeterRegistry`).
6. `HttpClientRegistryOptionalDepsTests` stays green (no optional types in bean signatures).

## 6. Version, docs, release

- **2.2.0 (MINOR)**, not 2.1.9: §4 adds public API (exception, listener, constructors, overload,
  meter). Poms move to `2.2.0-SNAPSHOT` in this PR; CHANGELOG gets `## [2.2.0]`.
- README: new "Token fetch: transport retry, typed failure, metrics" subsection under the token
  caching section; correct the Retry Behavior note (fact 6); add the counter to the metrics
  bullets and `TokenFetchMeters` to the application-scoped beans table.
- Out of scope, recorded for a separate row: status-code retry parity on the sync token path
  (fact 6); adopter mapping of `BearerTokenTransportException` → 503 (template row);
  `opentmf-versions` BOM bump (waits for the cut).
- Readiness before "cut ready": full `mvn clean verify` green on the exact sha with
  `JAVA_HOME=/opt/openjdk-bin-17`; **no CI workflow exists in this repo** — verification is local
  and is reported as such; Sonar scan zero new issues; both versions goals with every profile and
  the estate ignore string, `-U`; tree == `origin/develop` after merge; branch deleted after
  merge. Release only on Gökhan's typed go, with the accelerated recipe.
