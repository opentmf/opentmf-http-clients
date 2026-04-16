# Changelog

All notable changes to this project will be documented in this file.

This project is the successor to [opentmf-web-clients](https://github.com/opentmf/opentmf-web-clients) (v1x).
For migration guidance from the predecessor, see the [Migration from opentmf-web-clients](README.md#migration-from-v1x) section in the README.

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
