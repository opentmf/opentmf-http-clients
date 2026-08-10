# Changelog

All notable changes to this project will be documented in this file.

This project is the successor to [opentmf-web-clients](https://github.com/opentmf/opentmf-web-clients) (v1x).
For migration guidance from the predecessor, see the [Migration from opentmf-web-clients](README.md#migration-from-v1x) section in the README.

## [2.1.8] - 2026-08-10

### Changed
- **Build now enforces exact toolchain versions**: JDK 17.x and Maven 3.9.x (enforcer ranges
  `[17,18)` / `[3.9,3.10)`, previously minimums). The compiler's `release=17` already pinned
  bytecode and API level, but building on other JDKs left javadoc output and toolchain behaviour
  machine-dependent; now the build fails fast instead.

## [2.1.7] - 2026-08-10

### Added
- **W3C trace-context propagation on outbound calls.** Every library-built client — JDK and Apache
  `RestTemplate`, the derived `RestClient`, and the reactive `WebClient` (static, dynamic, and
  token clients alike) — is now wired to the application's Micrometer `ObservationRegistry` bean
  when one exists. Consumers running Micrometer-tracing therefore emit a `traceparent` header on
  every outbound request automatically, so the distributed trace continues across the HTTP hop
  instead of stopping at the client. Without an `ObservationRegistry` bean, or without a tracer,
  nothing changes: the library adds **no** new dependency (the observation API ships transitively
  with `spring-web`) and the observation is a no-op. Note that in applications where the registry
  carries metrics handlers (e.g. Spring Boot Actuator), outbound calls now also produce the
  standard `http.client.requests` observation metrics.

### Changed
- Dependency updates: resilience4j 2.3.0 → 2.4.0, Logbook 4.0.2 → 4.1.0,
  opentmf-commons 2.1.0 → 2.2.0.

## [2.1.6] - 2026-08-05

### Changed
- **Apache-backed clients no longer retry automatically at the transport level.** `client-type: apache`
  previously inherited Apache HttpClient's `DefaultHttpRequestRetryStrategy`, which silently retried
  429 and 503 once (honouring `Retry-After`) even when the caller had opted into no retries at all —
  and stacked on top of any caller-configured retry, so `num-retries: 3` meant up to 8 attempts on
  Apache versus 4 on the JDK and Netty backends. Retry policy now has exactly one owner, opted into
  at the call site via `SyncClientUtil.executeWithRetry(...)` / `WebClientUtil.retry(...)`, and all
  three backends behave identically. **Consumers relying on the implicit Apache retry must now opt
  in explicitly.**

### Added
- **`Retry-After` is honoured** by both retry utilities, on the statuses this library already
  considers retryable. The server controls *when*, the caller controls *how many* and *at most how
  long*: the header never changes the attempt budget, acts as a floor on the computed backoff (it
  may only ask the client to be more patient, never less), and a request to wait longer than the new
  `max-retry-after` fails fast instead of parking a thread. Both grammars are supported
  (`delay-seconds` and HTTP-date, the latter with clock-skew tolerance). A `Retry-After` arriving on
  a non-retryable status is logged at WARN and ignored, so it can never introduce a retry that was
  never requested.
- **`max-retry-after`** per client — longest server-requested delay to honour. Default 30s.
- **Response headers on the error path.** `OpenTmfClientResponseException` now carries the response
  headers and the parsed `Retry-After`. Errors are the only place these are reachable, since the
  response is closed before the caller sees it; on the success path headers remain the calling
  application's concern via `ResponseEntity`. `HttpClientUtil.remap(...)` carries them across to
  domain-specific exception types.
- **`Content-Type`-aware error parsing.** `ErrorBodyExtractor` now takes the charset from the
  response's `Content-Type` instead of assuming UTF-8, fixing garbled messages from non-UTF-8
  vendors. The header only informs the parse — the existing trial-parse fallback is retained,
  because servers mislabel error bodies routinely.

### Fixed
- An error handler no longer fails with `NullPointerException` (absent headers) or
  `InvalidMediaTypeException` (malformed `Content-Type`), either of which would have replaced the
  server's actual error with an unrelated one.
- The synchronous backoff no longer overflows: `baseMs * (1L << attempt)` is undefined once
  `attempt` reaches 63, and the shift is now bounded.

## [2.1.5] - 2026-07-30

### Fixed
- **`HttpClientRegistry` broke sync-only application contexts.** The reactive close action was an
  inline lambda capturing `ConnectionProvider`, which compiled to a synthetic method of the
  registry class whose signature references reactor-netty — an optional dependency. On any
  classpath without reactor-netty, Spring's bean introspection (`getDeclaredMethods()`) threw
  `NoClassDefFoundError` and the whole ApplicationContext failed to start. The close action now
  lives in its own lazily-loaded class, and a bytecode-level regression test asserts that no
  registry method signature references an optional-dependency package.

## [2.1.4] — 2026-07-29

### Added
- **`HttpClientRegistry`** — a registry-style lifecycle API for clients built programmatically at
  runtime (`getOrCreate` / `replace` / `evict`, plus reactive variants), complementing the
  create-once static beans. Retired clients close only after a 30s grace period so in-flight
  requests finish; closing is per-type (Apache: graceful pool close; JDK: guarded
  `AutoCloseable` close, effective on Java 21+ runtimes; Netty: graceful `ConnectionProvider`
  disposal, token client included). Replacing or evicting a name also resets its resilience4j
  instances.
- **`ClientPropertiesValidator`** — public, side-effect-free validation of a (programmatically
  built) client configuration returning ALL findings at once, for SRE "test connection"
  checklists. Flags, among others, `max-connections` on the JDK client type, where it is
  unenforceable — use `client-type: apache` or `resilience.bulkhead.max-concurrent-calls`.
- **Apache connection-pool gauges** — with a `MeterRegistry` bean, every Apache-backed client
  exposes `opentmf.client.pool.leased|available|pending|max{client=<name>}`.
- **Optional resilience4j integration** — per-client, config-driven circuit breaker, bulkhead and
  (reactive-only) time limiter under `opentmf.http-clients.<id>.resilience.*`. Off by default and
  classpath-gated: without the resilience4j jars or without `resilience.enabled: true`, behavior
  is unchanged. One configuration decorates every client shape of the id (`RestTemplate`,
  `RestClient`, `WebClient`) *and* its bearer-token calls, which share the same named instances.
  Only connection errors, timeouts and configured `record-status-codes` (default 500/502/503/504)
  trip the breaker — 4xx never does. Rejected calls throw the new
  `OpenTmfClientResilienceException` (outside the response-exception hierarchy, so
  `executeWithRetry`/`WebClientUtil.retry` never retry an open circuit). With a `MeterRegistry`
  and `resilience4j-micrometer` present, `resilience4j.circuitbreaker.*`/`resilience4j.bulkhead.*`
  meters are bound automatically. See the README "Resilience" section.
- Javadoc for every user-configurable property (`ClientProperties` and its nested types,
  `BasicAuthConfig`, `BearerAuthConfig`, `OpentmfHttpClientsConfig`), including the per-client-type
  behavioural differences (e.g. `request-timeout` is the TCP connect timeout on JDK/Netty but the
  pool-lease timeout on Apache; `max-connections` and `connection-idle-timeout` are ignored by the
  JDK client). The descriptions are exposed through Spring's configuration metadata
  (`spring-configuration-metadata.json`) for the `opentmf.*` root properties and through the
  published sources jar for the per-client map values, giving IDE auto-complete documentation when
  editing `application.yml`.

### Changed
- Internal code-quality pass driven by SonarQube: all findings resolved, code duplication reduced
  from 3.5% to 0% (shared `AbstractRestTemplateFactory` base class and `BearerTokenUtil` helpers),
  deprecated Jackson 3 / Spring API usages replaced. No functional changes.
- Build: the project now compiles on JDK 23+ (annotation processing enabled explicitly via
  `maven-compiler-plugin` with `proc=full`), targets Java 17 bytecode via
  `maven.compiler.release`, and offers an opt-in `sonar` profile for local SonarQube analysis.

## [2.1.3] — 2026-04-16

### Fixed
- `fixed-headers` no longer clobber per-request headers on the REST (`RestTemplate` / `RestClient`)
  side. The interceptor in `ApacheRestTemplateFactory` and `JdkRestTemplateFactory` now applies each
  fixed header only when the request hasn't already set it — aligning sync behavior with the reactive
  `WebClient`, which already treats `fixed-headers` as defaults. This unblocks OAuth token retrieval
  when `fixed-headers.Content-Type` is configured: `SyncTokenClientImpl`'s
  `application/x-www-form-urlencoded` is now preserved instead of being overwritten, so token
  endpoints no longer reject the request.

## [2.1.2] — 2026-04-06

### Fixed
- `RestLogbookAutoConfiguration` and `ReactiveLogbookAutoConfiguration` no longer use
  `@ConditionalOnBean(Logbook.class)`. The condition evaluated too early when the classes were
  processed via `@Import` — before Zalando's `LogbookAutoConfiguration` had created the `Logbook`
  bean. Replaced with `ObjectProvider<Logbook>` which defers resolution and falls back to
  `Logbook.create()` if no custom `Logbook` bean is configured. This fixes the "no Logbook bean
  found" warning even when `logbook-spring-boot-starter` is on the classpath.

## [2.1.1] — 2026-04-02

### Fixed
- Removed packaging:pom from the starter modules.

### Changed
- Bumped Spring Boot version to 4.0.5.

## [2.1.0] — 2026-03-25

### Changed
- Renamed `RestTemplateUtil` to `SyncClientUtil` — the class has no dependency on `RestTemplate` and works equally with both `RestClient` and `RestTemplate`. The new name better reflects its purpose as the synchronous counterpart to `WebClientUtil`.
- Renamed `BearerWebClientException` to `BearerTokenException` — the class has no dependency on `WebClient` and is a generic bearer-token error. The old name was a leftover from the predecessor project `opentmf-web-clients`.

---

## [2.0.0] — 2026-03-25

Initial release of `opentmf-http-clients`, a complete rewrite of the predecessor `opentmf-web-clients`.

### Highlights

- **Synchronous and reactive HTTP clients** — supports `RestClient` and `RestTemplate` (JDK HttpClient, Apache HttpClient 5) and `WebClient` (Reactor Netty), selectable via a single `client-type` property (`jdk`, `apache`, or `netty`). Aliases `rest`, `servlet`, and `reactive` are also accepted. `RestClient` is the recommended choice for synchronous workloads — it provides a modern fluent API and works naturally with virtual threads (Java 21+).
- **Unified configuration** — all clients defined in a single flat map under `opentmf.http-clients`, with auth type determined by the presence of a `basic-auth` or `bearer-auth` block (or neither for no auth).
- **Dynamic token caching** — Caffeine-based with per-token TTL derived from the OAuth2 `expires_in` response field. Configurable `cache-safety-factor` (default 0.9) and `fallback-expires-in-seconds` (default 3600).
- **Automatic error wrapping** — all library-created clients automatically convert HTTP error responses to `OpenTmfClientResponseException` (or `OpenTmfClientNotFoundException` for 404) with a human-readable message and raw response body.
- **Mutual TLS (mTLS)** — client certificate support for all three client types, compatible with forward proxy configuration (HTTP CONNECT tunneling).
- **Forward proxy support** — configurable per-client proxy with `proxy-host`, `proxy-port`, and `non-proxy-hosts`.
- **Logbook integration** — Zalando Logbook wired for all client types (REST via `LogbookClientHttpRequestInterceptor`, reactive via `LogbookClientHandler`). Controlled by the `logging-enabled` property (default `true`). Logbook is **fully optional** — the starters do not pull it in transitively; consumers must add the appropriate Logbook artifacts explicitly. Logbook wiring is isolated behind `@ConditionalOnClass`-guarded bridge beans to prevent classpath issues when Logbook is absent.
- **HTTP compression** — configurable via `compression-enabled` (default `true`). Netty uses its built-in `.compress()`, Apache uses its built-in content compression, and JDK HttpClient adds a transparent gzip interceptor.
- **Fixed headers** — the `fixed-headers` map is now applied to both REST and reactive clients uniformly.
- **Spring Boot 4.0.4** — built on Spring Framework 7, Jackson 3, and Logbook 4.0.2.

### Components

- `OpenTmfClientResponseException` — base exception for HTTP errors, carrying status code, message, and raw response body.
- `OpenTmfClientNotFoundException` — specialized 404 exception.
- `ErrorBodyExtractor` — intelligent error message extraction from JSON bodies (RFC 7807, TMF, OAuth2, Spring Boot default formats) with safe fallbacks for plain text and binary.
- `SyncClientUtil` — synchronous retry (`executeWithRetry`), error handling (`handleError`), `emptyOn404()`, `emptyOn(HttpStatus...)`). Works with both `RestClient` and `RestTemplate`.
- `OpenTmfRestClientStatusHandler` — `RestClient.ResponseSpec.ErrorHandler` for RestClient auto-wrapping.
- `WebClientUtil` — reactive retry, error handling, `emptyOn404()`, `emptyOn(HttpStatus...)`.
- `HttpClientUtil` — shared logic (retryable status codes, `remap()` for domain-specific exception conversion).
- `OpenTmfResponseErrorHandler` — `ResponseErrorHandler` for RestTemplate auto-wrapping.
- `WebClientConfigUtil.errorWrappingFilter()` — `ExchangeFilterFunction` for WebClient auto-wrapping, also available publicly for custom-built `WebClient` instances.
- `SyncTokenService` / `TokenService` — synchronous and reactive token service interfaces.
- `GzipClientHttpRequestInterceptor` — transparent gzip decompression interceptor for JDK HttpClient RestTemplate/RestClient.
- `RestTemplateFactory` with `ApacheRestTemplateFactory` and `JdkRestTemplateFactory` implementations.
- Modular starter architecture: `opentmf-http-clients-starter-rest` (REST-only), `opentmf-http-clients-starter-reactive` (reactive-only), and `opentmf-http-clients-starter` (umbrella). Auto-configuration lives in `opentmf-http-clients-autoconfigure`.

---

## Prior releases (opentmf-web-clients)

The following versions were released under the predecessor project `opentmf-web-clients`.

### [1.1.1]

#### Changed
- Added missing descriptions into pom.xml files.

### [1.1.0]

#### Changed
- First open-source release, replacing `pia` with `opentmf` namespace.

### [1.0.9]

#### Added
- Mutual TLS protocol support.

### [1.0.8]

#### Changed
- Stopped depending on `spring-boot-starter-webflux` to support synchronous spring-web applications (`web-application-type = servlet`) with fewer dependencies, avoiding potential webflux auto-configurations.

### [1.0.7]

#### Changed
- Started exposing beans only if they are not already exposed, to help test cases run in parallel.

### [1.0.6]

#### Added
- Marker beans for starter packages.

### [1.0.5]

#### Added
- `opentmf-basic-webclients-starter` that dynamically exposes beans from configuration.
- `opentmf-openid-webclients-starter` that dynamically exposes beans from configuration.

#### Fixed
- Providers' local caching issue when multiple connections are configured.

#### Removed
- Configuration property `cacheName` from `OpenidTokenProperties`.

### [1.0.4]

#### Added
- Configuration property `usernameField` to `OpenidTokenProperties`.

### [1.0.3]

#### Fixed
- Marked `PiaWebClientException` as `Serializable`.

### [1.0.2]

#### Changed
- Moved `getToken(scope)` method to generic layer.

### [1.0.1]

#### Fixed
- Corrected the `BasicWebClientProviderAutoConfiguration` class name.

### [1.0.0]

#### Added
- Initial release.
