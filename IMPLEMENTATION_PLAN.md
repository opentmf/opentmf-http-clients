# Implementation Plan: opentmf-http-clients v2.0.0

## Overview

`opentmf-http-clients` is a complete rewrite of [opentmf-web-clients](https://github.com/opentmf/opentmf-web-clients) (v1.x). It provides general-purpose HTTP client libraries for Spring Boot with:

- **Reactive** (`WebClient` / Reactor Netty) and **synchronous** (`RestClient` / `RestTemplate` / JDK HttpClient or Apache HttpClient 5) clients
- Unified configuration under a single flat map (`opentmf.http-clients`)
- Authentication: Bearer (OAuth2/OIDC with Caffeine-based token caching), Basic, or None
- Pluggable HTTP transport selected by a single `client-type` property (`jdk`, `apache`, `netty`)
- Automatic error wrapping, mTLS, forward proxy, Logbook logging, HTTP compression, fixed headers
- Spring Boot 4.0.4, Spring Framework 7, Jackson 3, Logbook 4.0.2

## Technology Stack

| Component | Version |
|-----------|---------|
| Java | 17+ |
| Spring Boot | 4.0.4 |
| Spring Framework | 7 |
| Jackson | 3 (via opentmf-commons 2.1.0) |
| Logbook | 4.0.2 |
| Caffeine | managed by Spring Boot BOM |
| Apache HttpClient 5 | managed by Spring Boot BOM |
| Reactor Netty | managed by Spring Boot BOM |
| MockServer Netty | 5.15.0 (tests) |
| opentmf-mockserver | 2.1.1 (tests) |

## Module Structure

```
opentmf-http-clients/                          # Root (POM)
├── opentmf-http-clients-common/               # Shared models, exceptions, utilities (no reactor, no RestTemplate)
│   ├── model/
│   │   ├── ClientProperties                   # Unified configuration POJO
│   │   ├── BasicAuthConfig                    # Basic auth credentials
│   │   ├── BearerAuthConfig                   # OAuth2 bearer token config
│   │   ├── AuthType                           # Enum: NONE, BASIC, BEARER
│   │   └── ClientType                         # Enum: JDK, APACHE, NETTY (with aliases)
│   ├── exception/
│   │   ├── OpenTmfClientResponseException     # Base HTTP error exception
│   │   └── OpenTmfClientNotFoundException     # 404 specialization
│   └── util/
│       ├── ErrorBodyExtractor                 # Intelligent JSON error message extraction
│       ├── HttpClientUtil                     # Shared: retryable statuses, remap, exception creation
│       └── TokenUtil                          # Constants and cache key builder
│
├── opentmf-http-clients-rest/                 # Synchronous (RestClient / RestTemplate) layer
│   ├── service/api/
│   │   ├── SyncTokenService                   # Synchronous token interface
│   │   └── RestTemplateFactory                # Factory interface for pluggable HTTP backends
│   ├── service/impl/
│   │   ├── NoOpSyncTokenService               # No-auth token service
│   │   └── SyncBasicTokenServiceImpl          # Basic auth token service
│   └── util/
│       ├── RestTemplateUtil                   # Retry, error handling, emptyOn404, emptyOn
│       ├── OpenTmfResponseErrorHandler        # ResponseErrorHandler for RestTemplate auto-wrapping
│       ├── OpenTmfRestClientStatusHandler     # ErrorHandler for RestClient auto-wrapping
│       └── GzipClientHttpRequestInterceptor   # Transparent gzip decompression for JDK HttpClient
│
├── opentmf-http-clients-reactive/             # Reactive (WebClient / Reactor Netty) layer
│   ├── service/api/
│   │   └── TokenService                       # Reactive token interface (Mono<String>)
│   ├── service/impl/
│   │   ├── NoOpTokenService                   # No-auth token service
│   │   └── BasicTokenServiceImpl              # Basic auth token service
│   └── util/
│       ├── WebClientConfigUtil                # Builds HttpClient + WebClient from properties
│       └── WebClientUtil                      # Retry, error handling, emptyOn404, emptyOn
│
├── opentmf-http-clients-bearer-provider/      # Bearer token retrieval with Caffeine caching
│   ├── model/
│   │   └── TokenEntry                         # Cache entry: token data + computed TTL
│   ├── exception/
│   │   └── BearerWebClientException           # Bearer-specific exception
│   ├── reactive/
│   │   ├── BearerTokenClient                  # Interface
│   │   ├── BearerTokenClientImpl              # WebClient-based token retrieval
│   │   ├── BearerTokenService                 # Extended reactive token service interface
│   │   ├── BearerTokenServiceImpl             # Reactive token service with caching
│   │   └── BearerTokenServiceMockImpl         # Mock implementation
│   ├── sync/
│   │   ├── SyncBearerTokenService             # Extended sync token service interface
│   │   ├── SyncBearerTokenServiceImpl         # Sync token service with caching
│   │   ├── SyncBearerTokenServiceMockImpl     # Mock implementation
│   │   └── SyncTokenClientImpl                # RestClient-based token retrieval
│   └── util/
│       ├── BearerTokenUtil                    # Scope/username resolution helpers
│       └── TokenCacheUtil                     # Shared Caffeine Expiry factory
│
├── opentmf-http-clients-autoconfigure/        # Spring Boot auto-configuration
│   ├── OpentmfHttpClientsAutoConfiguration    # Top-level @AutoConfiguration entry point
│   ├── OpentmfHttpClientsConfig               # @ConfigurationProperties(prefix = "opentmf")
│   ├── CommonBeanRegistrar                    # Iterates client map, dispatches to registrars
│   ├── StringToClientTypeConverter            # Spring Converter for client-type aliases
│   ├── reactive/
│   │   └── ReactiveClientRegistrar            # @ConditionalOnClass(WebClient.class)
│   └── rest/
│       ├── RestClientRegistrar                # @ConditionalOnClass(RestTemplate.class)
│       ├── ApacheRestTemplateFactory          # @ConditionalOnClass(CloseableHttpClient)
│       └── JdkRestTemplateFactory             # Always available (JDK 11+)
│
├── opentmf-http-clients-starter-rest/         # POM starter: REST-only (includes logbook-spring)
├── opentmf-http-clients-starter-reactive/     # POM starter: reactive-only (includes logbook-netty)
├── opentmf-http-clients-starter/              # POM starter: umbrella (REST + reactive + httpclient5)
└── opentmf-http-clients-tests/                # Integration tests
```

## Configuration Model

### Global defaults

```yaml
opentmf:
  client-type: jdk               # jdk (default) | apache | netty
```

Aliases: `rest` and `servlet` map to `jdk`; `reactive` maps to `netty`.

### Per-client configuration

All clients are defined under `opentmf.http-clients.<clientId>`:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `client-type` | string | *(global)* | Override the global default |
| `base-url` | string | *(none)* | Base URL prepended to all relative URIs |
| `max-connections` | int | `200` | Connection pool size |
| `max-connections-per-route` | int | *(max-connections)* | Per-route limit (Apache only) |
| `request-timeout` | Duration | `30s` | Connect timeout |
| `response-timeout` | Duration | `45s` | Read/response timeout |
| `connection-idle-timeout` | Duration | `4m` | Idle connection eviction |
| `num-retries` | int | `3` | Max retry count for `executeWithRetry` |
| `retry-wait-duration` | Duration | `5s` | Base wait between retries |
| `follow-redirects` | boolean | `true` | Follow HTTP 3xx |
| `ssl-protocol` | string | `TLS` | SSL/TLS protocol version |
| `logging-enabled` | boolean | `true` | Logbook request/response logging |
| `compression-enabled` | boolean | `true` | HTTP gzip compression |
| `fixed-headers` | map | *(none)* | Headers added to every request |
| `proxy-config` | object | *(none)* | Forward proxy settings |
| `certificates` | object | *(none)* | mTLS key-store and trust-store |
| `basic-auth` | object | *(none)* | Basic auth credentials |
| `bearer-auth` | object | *(none)* | OAuth2 bearer token config |
| `paths` | map | *(none)* | Named path/scope entries |

Duration values accept Spring Boot duration strings: `500ms`, `3s`, `1m`, `PT30S`.

## Exposed Beans

For each `clientId` in the map:

### Reactive (`client-type: netty`)

| Bean Name | Type |
|-----------|------|
| `{clientId}ClientProperties` | `ClientProperties` |
| `{clientId}WebClient` | `WebClient` |
| `{clientId}TokenService` | `TokenService` |

### REST (`client-type: jdk` or `apache`)

| Bean Name | Type |
|-----------|------|
| `{clientId}ClientProperties` | `ClientProperties` |
| `{clientId}RestClient` | `RestClient` (recommended) |
| `{clientId}RestTemplate` | `RestTemplate` (deprecated in Spring Framework 7) |
| `{clientId}TokenService` | `SyncTokenService` |

A single client produces **either** reactive **or** REST beans, never both.

## Auto-Configuration Architecture

```
OpentmfHttpClientsAutoConfiguration          ← @AutoConfiguration, always loaded
├── CommonBeanRegistrar                      ← iterates clients map, dispatches
│   ├── if clientType.isReactive() →
│   │   └── ReactiveClientRegistrar          ← @ConditionalOnClass(WebClient.class)
│   │       └── registers: WebClient, TokenService
│   └── else →
│       └── RestClientRegistrar              ← @ConditionalOnClass(RestTemplate.class)
│           ├── selects factory by ClientType:
│           │   ├── ApacheRestTemplateFactory ← @ConditionalOnClass(CloseableHttpClient)
│           │   └── JdkRestTemplateFactory   ← always available
│           └── registers: RestTemplate, RestClient, SyncTokenService
```

### Startup diagnostics

- **ERROR + fail**: if a reactive client is configured but `spring-webflux` is not on the classpath
- **ERROR + fail**: if `client-type: apache` is configured but `httpclient5` is not on the classpath
- **WARN**: if `logging-enabled: true` but no Logbook bean is found (Logbook jar absent)

## Feature Parity: REST vs Reactive

Both REST and reactive clients are configured uniformly from the same `ClientProperties`. The following features apply to all client types:

| Feature | Netty (reactive) | JDK (REST) | Apache (REST) |
|---------|-------------------|------------|---------------|
| Logbook logging | `LogbookClientHandler` | `LogbookClientHttpRequestInterceptor` | `LogbookClientHttpRequestInterceptor` |
| Fixed headers | `WebClient.defaultHeaders()` | `ClientHttpRequestInterceptor` | `ClientHttpRequestInterceptor` |
| Compression | `.compress(flag)` | `GzipClientHttpRequestInterceptor` | Built-in (disable via `disableContentCompression()`) |
| Error wrapping | `ExchangeFilterFunction` | `OpenTmfResponseErrorHandler` + `OpenTmfRestClientStatusHandler` | Same |
| mTLS | Netty `SslContext` | JDK `SSLContext` | Apache `TlsSocketStrategy` |
| Proxy | Reactor Netty proxy | JDK `ProxySelector` | Apache `DefaultProxyRoutePlanner` |
| `max-connections` | Honoured | Ignored (JDK manages internally) | Honoured |
| `follow-redirects` | Honoured | Honoured | Honoured |
| `connection-idle-timeout` | Honoured | N/A | Honoured |

## Dynamic Token Caching

Bearer tokens are cached using Caffeine with per-entry variable TTL derived from the OAuth2 `expires_in` response field:

```
TTL = expires_in * cache-safety-factor (default 0.9)
```

If `expires_in` is absent, `fallback-expires-in-seconds` (default 3600) is used. Minimum TTL is 1 second.

The `Expiry<String, TokenEntry>` implementation lives in `TokenCacheUtil` (shared by both registrars):
- `expireAfterCreate`: uses `entry.getCacheDuration().toNanos()`
- `expireAfterUpdate`: uses `entry.getCacheDuration().toNanos()` (resets TTL on refresh)
- `expireAfterRead`: returns `currentDuration` (reads do not extend TTL)

Cache keys incorporate `tokenUrl + scope + username` for isolation.

## Error Handling

All library-created clients automatically convert HTTP error responses:
- 404 → `OpenTmfClientNotFoundException`
- Other 4xx/5xx → `OpenTmfClientResponseException`

Both carry: HTTP status code, human-readable message (extracted via `ErrorBodyExtractor`), and raw response body.

`ErrorBodyExtractor` recognizes: RFC 7807 Problem Details, TMF Open API, OAuth2, Spring Boot, and generic JSON error formats.

## Retry Behavior

Retries are **opt-in** at call sites (not automatic):
- Reactive: `WebClientUtil.retry(numRetries, retryWaitDuration)`
- REST: `RestTemplateUtil.executeWithRetry(supplier, numRetries, retryWaitDuration)`

Retryable HTTP status codes: 408, 429, 500, 502, 503, 504, 509.

The only internal retry is on bearer token retrieval.

## Logbook Integration

Logbook is **optional** at the library layer. When `logging-enabled: true` and a `Logbook` bean is available:
- REST clients: `LogbookClientHttpRequestInterceptor` added to `RestTemplate`
- Reactive clients: `LogbookClientHandler` added to Netty pipeline

Required artifacts per client type:
- REST (`jdk`, `apache`): `org.zalando:logbook-spring`
- Reactive (`netty`): `org.zalando:logbook-netty`

The starters include the appropriate Logbook dependencies. If Logbook is absent, a WARN is logged and logging is silently skipped.

## HTTP Compression

Controlled by `compression-enabled` (default `true`):
- **Netty**: Reactor Netty built-in `.compress(flag)`
- **Apache**: Built-in content compression; `disableContentCompression()` when disabled
- **JDK**: `GzipClientHttpRequestInterceptor` adds `Accept-Encoding: gzip` and transparently decompresses. Placed before Logbook interceptor so Logbook logs decompressed content.

## Mutual TLS Support

All three client types support mTLS via `certificates.key-store` and optional `certificates.trust-store`. mTLS works with forward proxy (HTTP CONNECT tunneling — TLS handshake is end-to-end through the tunnel).

## Starter Dependency Strategy

```
opentmf-http-clients-starter              # Umbrella (REST + reactive + httpclient5)
├── opentmf-http-clients-starter-rest     # REST: autoconfigure + rest + logbook-spring
│   └── (users add httpclient5 if needed)
└── opentmf-http-clients-starter-reactive # Reactive: autoconfigure + reactive + webflux + reactor-netty + logbook-netty
```

Users pick the starter matching their stack:
- REST-only: `opentmf-http-clients-starter-rest`
- Reactive-only: `opentmf-http-clients-starter-reactive`
- Both: `opentmf-http-clients-starter`

## Test Coverage

### Integration tests (`opentmf-http-clients-tests`)

- All client types (JDK, Apache, Netty) with all auth types (bearer, basic, none)
- `RestClient` and `RestTemplate` utility methods (`executeWithRetry`, `emptyOn404`, `emptyOn`, error handling)
- `WebClient` utility methods (reactive retry, error handling)
- mTLS for all client types
- mTLS with forward proxy for all client types
- Bearer token retrieval with realistic Keycloak mock (`opentmf-mockserver`)

### Per-module unit/integration tests

- **common**: `ClientPropertiesTest`, `ClientPropertiesNestedModelsTest`, `ErrorBodyExtractorTest`, `TokenUtilTest`, `HttpClientUtilTest`
- **rest**: `RestTemplateUtilTest`, `OpenTmfResponseErrorHandlerTest`, `OpenTmfRestClientStatusHandlerTest`, `GzipClientHttpRequestInterceptorTest`
- **reactive**: `WebClientUtilTest`, `WebClientConfigUtilIT`
- **bearer-provider**: `BearerTokenServiceIT`, `SyncBearerTokenServiceIT`, `TokenEntryTest`, `TokenCacheExpiryTest`, mock impl tests
- **autoconfigure**: `AutoConfigurationIT`, `OpentmfHttpClientsConfigTest`, `JdkRestTemplateFactoryTest`, `ApacheRestTemplateFactoryTest`

### Cache eviction tests (`TokenCacheExpiryTest`)

- TTL-based eviction with production `Expiry` (using fake `Ticker`)
- Per-entry variable TTL (short-lived vs long-lived entries evict independently)
- `expireAfterRead` does not extend TTL
- Cache key isolation by scope and by username
- Update resets TTL via `expireAfterUpdate`

### JaCoCo

- `<haltOnFailure>true</haltOnFailure>` enforced per module
- `**/*Application.*` classes excluded

## Implementation Status

All features listed above are **implemented and passing** `mvn clean verify`.

### Key decisions made during implementation

1. **Single `client-type` property** — consolidated `client-type` + `http-client-type` into one property with three values (`jdk`, `apache`, `netty`) and aliases (`rest`, `servlet`, `reactive`).

2. **Configuration prefix `opentmf.http-clients`** — chosen over `opentmf.clients` to avoid confusion with `opentmf-clients-base` (TMF domain clients).

3. **`RestClient` as first-class citizen** — registered alongside `RestTemplate` for each REST client. Recommended for new code; `RestTemplate` is deprecated in Spring Framework 7.

4. **`SyncTokenClientImpl` uses `RestClient`** — the internal bearer token fetching client uses `RestClient` (not `RestTemplate`) for future-proofing. It reuses the main `{clientId}RestClient` bean.

5. **Logbook optional at library layer** — `logbook-spring` and `logbook-netty` are `<optional>true</optional>` in autoconfigure POM, but included non-optionally in the respective starters.

6. **`TokenCacheUtil` shared cache factory** — the production `Expiry<String, TokenEntry>` implementation extracted from both registrars into a shared utility, eliminating duplication and enabling direct unit testing.

7. **`max-connections` default 200** — lowered from initial 500. Netty honours it (increase for high-throughput reactive). Apache honours it (lower for synchronous). JDK ignores it.

8. **Dynamic bean registration** — all client beans registered via `registerSingleton()`. IDE "Could not autowire" warnings are harmless. `@DependsOn("opentmfHttpClientsStarter")` available for ordering.
