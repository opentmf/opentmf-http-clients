# opentmf-http-clients

General-purpose HTTP client libraries for Spring Boot with Logbook integration, configurable connection properties, fixed headers, and token retrieval with implicit access token caching.

Supports both **reactive** (`WebClient`) and **synchronous** (`RestClient` / `RestTemplate`) clients with pluggable HTTP implementations, unified under a single configuration model.

**Authentication types:**

1. **Bearer Auth** — OAuth2/OIDC bearer tokens with dynamic Caffeine-based caching
2. **Basic Auth** — username/password Basic authentication
3. **No Auth** — unauthenticated clients

> This project replaces [opentmf-web-clients](https://github.com/opentmf/opentmf-web-clients) (v1.x). See the [Migration from v1.x](#migration-from-v1x) section for upgrade instructions.

## Modules

| Module | Description |
|---|---|
| `opentmf-http-clients-common` | Shared models, exceptions, and utilities (reactor-free, RestTemplate-free) |
| `opentmf-http-clients-rest` | Synchronous (RestClient / RestTemplate) interfaces and utilities |
| `opentmf-http-clients-reactive` | Reactive (WebClient / Reactor Netty) interfaces and utilities |
| `opentmf-http-clients-bearer-provider` | Bearer token retrieval with Caffeine caching (reactive and sync) |
| `opentmf-http-clients-autoconfigure` | Spring Boot auto-configuration (config properties, bean registrars) |
| `opentmf-http-clients-starter-rest` | Starter for REST-only projects |
| `opentmf-http-clients-starter-reactive` | Starter for reactive-only projects |
| `opentmf-http-clients-starter` | Umbrella starter (REST + reactive) |
| `opentmf-http-clients-tests` | Integration tests |

## Usage

### Import opentmf-commons dependency versions

This will manage the dependencies of the opentmf-commons libraries to use their latest compatible version.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.opentmf</groupId>
      <artifactId>opentmf-versions</artifactId>
      <version>RELEASE</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

### Add a starter dependency

Pick one of the three starters depending on your needs:

**REST-only** (RestClient + RestTemplate / JDK or Apache HttpClient):

```xml
<dependency>
  <groupId>org.opentmf.client</groupId>
  <artifactId>opentmf-http-clients-starter-rest</artifactId>
  <type>pom</type>
</dependency>
```

**Reactive-only** (WebClient / Reactor Netty):

```xml
<dependency>
  <groupId>org.opentmf.client</groupId>
  <artifactId>opentmf-http-clients-starter-reactive</artifactId>
  <type>pom</type>
</dependency>
```

**Both REST and reactive** (umbrella):

```xml
<dependency>
  <groupId>org.opentmf.client</groupId>
  <artifactId>opentmf-http-clients-starter</artifactId>
  <type>pom</type>
</dependency>
```

For **REST** clients, the default transport is JDK HttpClient (Java 11+) — no extra dependency needed. If you prefer Apache HttpClient 5 (e.g. for advanced connection pool tuning), add it explicitly:

```xml
<dependency>
  <groupId>org.apache.httpcomponents.client5</groupId>
  <artifactId>httpclient5</artifactId>
</dependency>
```

> The **umbrella starter** (`opentmf-http-clients-starter`) already bundles `httpclient5`. This is only needed when using `opentmf-http-clients-starter-rest` directly.

## Configuration

All clients are defined in a single flat map under `opentmf.http-clients`. Authentication type is determined by the presence of a `basic-auth` or `bearer-auth` block (or neither for no auth).

### Global defaults

```yaml
opentmf:
  client-type: jdk               # jdk (default) | apache | netty
```

Each client can override this via a per-client `client-type` property. The three options are:

| Value | Client created | HTTP transport | Aliases |
|-------|---------------|----------------|---------|
| `jdk` (default) | `RestTemplate` | JDK HttpClient | `rest`, `servlet` |
| `apache` | `RestTemplate` | Apache HttpClient 5 | — |
| `netty` | `WebClient` | Reactor Netty | `reactive` |

### Per-client properties

| Property | Type | Default | Description |
|---|---|---|---|
| `client-type` | string | *(global)* | Override the global default for this client |
| `base-url` | string | *(none)* | Base URL prepended to all relative request URIs |
| `max-connections` | int | `200` | Maximum number of connections in the pool |
| `max-connections-per-route` | int | *(max-connections)* | Per-route connection limit (Apache HttpClient only, ignored by others) |
| `request-timeout` | Duration | `30s` | Connect timeout |
| `response-timeout` | Duration | `45s` | Read / response timeout |
| `connection-idle-timeout` | Duration | `4m` | How long idle connections stay in the pool before eviction |
| `num-retries` | int | `3` | Maximum retry count (used by `executeWithRetry` / `WebClientUtil.retry`) |
| `retry-wait-duration` | Duration | `5s` | Base wait between retries (exponential backoff) |
| `follow-redirects` | boolean | `true` | Automatically follow HTTP 3xx redirects |
| `ssl-protocol` | string | `TLS` | SSL/TLS protocol version (`TLS`, `TLSv1.2`, `TLSv1.3`) |
| `logging-enabled` | boolean | `true` | Enable Logbook request/response logging for this client |
| `compression-enabled` | boolean | `true` | Enable HTTP response compression (gzip) |
| `fixed-headers` | map | *(none)* | Headers added to every request |
| `proxy-config` | object | *(none)* | Forward proxy settings (`proxy-host`, `proxy-port`, `non-proxy-hosts`) |
| `certificates` | object | *(none)* | mTLS key-store and trust-store |
| `basic-auth` | object | *(none)* | Basic authentication credentials |
| `bearer-auth` | object | *(none)* | OAuth2 bearer token configuration |
| `paths` | map | *(none)* | Named path/scope entries |
| `resilience` | object | *(disabled)* | Optional resilience4j circuit breaker / bulkhead / time limiter — see [Resilience](#resilience-circuit-breaker--bulkhead) |

Duration values accept Spring Boot duration strings: `500ms`, `3s`, `1m`, `PT30S`.

> **`max-connections` behaviour per client type:**
> - **Netty** (`client-type: netty`): fully honoured. For high-throughput reactive workloads, consider increasing to 500+.
> - **Apache** (`client-type: apache`): fully honoured. Apache HttpClient's own default is 25; for synchronous clients, lower values (25-100) are often more appropriate to avoid excessive blocking threads.
> - **JDK** (`client-type: jdk`): ignored. The JDK HttpClient manages connection pooling internally and does not expose a pool-size setting.

> **`compression-enabled` behaviour per client type:**
> - **Netty** (`client-type: netty`): sets Reactor Netty's `.compress(true/false)`. When enabled, the client sends `Accept-Encoding: gzip, deflate` and decompresses responses transparently.
> - **Apache** (`client-type: apache`): Apache HttpClient 5 enables gzip/deflate decompression by default. When `compression-enabled: false`, the library calls `disableContentCompression()`.
> - **JDK** (`client-type: jdk`): the library adds an interceptor that sets `Accept-Encoding: gzip` on outgoing requests and transparently decompresses gzipped responses. When `compression-enabled: false`, the interceptor is not installed.

> **Logbook HTTP logging:**
> When `logging-enabled: true` (the default), the library wires [Zalando Logbook](https://github.com/zalando/logbook) to log HTTP requests and responses. Logbook is **fully optional** — the starters do **not** pull it in transitively. To enable HTTP logging, add the appropriate Logbook dependency to your project:
>
> | Client type | Required dependency |
> |---|---|
> | `jdk`, `apache` (REST) | `org.zalando:logbook-spring-boot-autoconfigure` |
> | `netty` (reactive) | `org.zalando:logbook-netty` + `org.zalando:logbook-spring-boot-autoconfigure` |
>
> `logbook-spring-boot-autoconfigure` transitively provides `logbook-spring`, `logbook-core`, and the `Logbook` bean auto-configuration. For reactive clients, `logbook-netty` must be added separately as it's not included transitively.
>
> If Logbook is not on the classpath, a WARN is logged at startup and no logging interceptor is installed — the client works normally without it.

### Bearer Auth Client (Minimal)

```yaml
opentmf:
  http-clients:
    myBearerClient:
      bearer-auth:
        token-url: http://localhost:1080/token
        form-data:
          username: user
          password: pass
          scope: openid
          grant_type: password
```

### Bearer Auth Client (Full)

```yaml
opentmf:
  http-clients:
    myBearerClient:
      client-type: netty
      base-url: https://api.example.com
      max-connections: 100
      request-timeout: 50s
      response-timeout: 50s
      connection-idle-timeout: 4m
      num-retries: 3
      retry-wait-duration: 5s
      follow-redirects: true
      ssl-protocol: TLS
      logging-enabled: true
      compression-enabled: true
      fixed-headers:
        Accept: application/json
        AnotherHeader: AnotherValue
      paths:
        getCatalog:
          path: /catalog
          scope: GET_CATALOG_SCOPE
        postCatalog:
          path: /catalog
          scope: POST_CATALOG_SCOPE
      proxy-config:
        proxy-host: localhost
        proxy-port: 1234
        non-proxy-hosts:
          - mockserver
          - camunda7
      bearer-auth:
        use-mock: false
        token-url: http://localhost:1080/token
        client-id: myClientId
        client-secret: myClientSecret
        token-field: access_token
        username-field: username
        fallback-expires-in-seconds: 3600
        cache-safety-factor: 0.9
        form-data:
          username: user
          password: pass
          scope: openid
          grant_type: password
      certificates:
        key-store:
          password: mypassword
          pk-password: mypassword
          base64-jks: <BASE64_JKS_CONTENT>
        trust-store:
          password: mypassword
          base64-jks: <BASE64_JKS_CONTENT>
```

### Basic Auth Client (Minimal)

```yaml
opentmf:
  http-clients:
    myBasicClient:
      basic-auth:
        username: user
        password: pass
```

### Basic Auth Client (Full)

```yaml
opentmf:
  http-clients:
    myBasicClient:
      max-connections: 100
      request-timeout: 50s
      response-timeout: 50s
      num-retries: 3
      retry-wait-duration: 5s
      fixed-headers:
        Accept: application/json
      proxy-config:
        proxy-host: localhost
        proxy-port: 1234
        non-proxy-hosts:
          - mockserver
      basic-auth:
        username: user
        password: pass
        charset: UTF-8
      certificates:
        key-store:
          password: mypassword
          pk-password: mypassword
          base64-jks: <BASE64_JKS_CONTENT>
        trust-store:
          password: mypassword
          base64-jks: <BASE64_JKS_CONTENT>
```

### No-Auth Client

```yaml
opentmf:
  http-clients:
    healthCheck:
      request-timeout: 5s
      response-timeout: 10s
```

### REST Client with Bearer Auth

```yaml
opentmf:
  client-type: apache
  http-clients:
    syncClient:
      bearer-auth:
        token-url: https://auth.example.com/token
        client-id: federation
        client-secret: mySecret
        form-data:
          grant_type: client_credentials
          scope: openid
```

### Mixed Reactive and REST Clients

```yaml
opentmf:
  client-type: netty
  http-clients:
    reactiveApi:
      bearer-auth:
        token-url: https://auth.example.com/token
        client-id: federation
        client-secret: mySecret
        form-data:
          grant_type: client_credentials

    syncApi:
      client-type: apache
      bearer-auth:
        token-url: https://auth.example.com/token
        client-id: federation
        client-secret: mySecret
        form-data:
          grant_type: client_credentials

    healthCheck:
      client-type: jdk
```

## Exposed Beans

For each entry in the `http-clients` map with key `clientId`, the registered beans depend on the effective `client-type`:

### Reactive clients (`client-type: netty`)

| Bean Name | Type |
|---|---|
| `{clientId}ClientProperties` | `ClientProperties` |
| `{clientId}WebClient` | `WebClient` |
| `{clientId}TokenService` | `TokenService` |

### REST clients (`client-type: jdk` or `client-type: apache`)

| Bean Name | Type |
|---|---|
| `{clientId}ClientProperties` | `ClientProperties` |
| `{clientId}RestClient` | `RestClient` (recommended) |
| `{clientId}RestTemplate` | `RestTemplate` (deprecated in Spring Framework 7) |
| `{clientId}TokenService` | `SyncTokenService` |

Both a `RestClient` and a `RestTemplate` bean are registered for each REST client. The `RestClient` is the recommended choice for new code — it provides a modern fluent API, works naturally with virtual threads (Java 21+), and is the official replacement for `RestTemplate` in Spring Framework 7+.

A single client produces **either** reactive beans **or** REST beans, never both. If you need both a `WebClient` and a `RestClient` for the same backend, declare two clients with different IDs (e.g. one with `client-type: netty` and one with `client-type: jdk`).

### Autowiring beans

When the field name matches the bean name exactly, Spring resolves it by name — no `@Qualifier` is needed:

```java
@RequiredArgsConstructor
public class MyCatalogService {

  private final ClientProperties reactiveApiClientProperties;
  private final WebClient reactiveApiWebClient;
  private final TokenService reactiveApiTokenService;

  private final RestClient syncApiRestClient;     // recommended
  private final RestTemplate syncApiRestTemplate;  // deprecated
  private final SyncTokenService syncApiTokenService;
  // ...
}
```

If the field name does not match the bean name, use `@Qualifier` explicitly:

```java
@Autowired
@Qualifier("reactiveApiWebClient")
private WebClient catalogClient;
```

> **IDE warnings:** Your IDE may report "Could not autowire" warnings for these beans.
> This is a **false positive** — the beans are registered dynamically at runtime via
> `registerSingleton()`, which the IDE's static analysis cannot detect. The beans are
> resolved correctly at runtime. You can safely suppress these warnings with
> `@SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")` on the class if desired.

### Bean ordering

Because the library registers beans dynamically (via `registerSingleton()`), Spring cannot see them during the bean definition phase. In rare cases, your bean might be instantiated before the library has registered its beans, causing an autowiring failure.

To guarantee ordering, add `@DependsOn("opentmfHttpClientsStarter")` to your class. This forces the library's auto-configuration — and all dynamic bean registration — to complete first:

```java
@Service
@DependsOn("opentmfHttpClientsStarter")
public class CatalogService {

  private final WebClient catalogWebClient;

  public CatalogService(WebClient catalogWebClient) {
    this.catalogWebClient = catalogWebClient;
  }
}
```

> In most applications this is not necessary — the auto-configuration runs early enough
> that the beans are available by the time your components are created. Use `@DependsOn`
> only if you encounter a `NoSuchBeanDefinitionException` at startup for a bean you
> know is configured.

### Customizing clients

The library creates fully configured clients from your YAML properties. You can further customize them after injection, or replace them entirely.

#### WebClient (Reactor Netty)

`WebClient` is immutable — call `mutate()` to derive a customized copy that shares the same underlying connection pool:

```java
@Service
@DependsOn("opentmfHttpClientsStarter")
public class CatalogService {

  private final WebClient webClient;

  public CatalogService(WebClient reactiveApiWebClient) {
    this.webClient = reactiveApiWebClient.mutate()
        .defaultHeader("X-Tenant", "acme")
        .filter(myLoggingFilter())
        .build();
  }
}
```

#### RestClient (JDK or Apache)

`RestClient` is immutable — call `mutate()` to derive a customized copy:

```java
@Service
@DependsOn("opentmfHttpClientsStarter")
public class CatalogService {

  private final RestClient restClient;

  public CatalogService(RestClient syncApiRestClient) {
    this.restClient = syncApiRestClient.mutate()
        .defaultHeader("X-Tenant", "acme")
        .requestInterceptor((request, body, execution) -> {
          request.getHeaders().set("X-Correlation-Id", UUID.randomUUID().toString());
          return execution.execute(request, body);
        })
        .build();
  }
}
```

#### RestTemplate (JDK or Apache) — deprecated

`RestTemplate` is mutable — add interceptors or message converters directly:

```java
@Service
@DependsOn("opentmfHttpClientsStarter")
public class CatalogService {

  private final RestTemplate restTemplate;

  public CatalogService(RestTemplate syncApiRestTemplate) {
    this.restTemplate = syncApiRestTemplate;
    this.restTemplate.getInterceptors().add((request, body, execution) -> {
      request.getHeaders().set("X-Tenant", "acme");
      return execution.execute(request, body);
    });
  }
}
```

#### Replacing a client entirely

If the YAML-driven configuration is not sufficient (e.g. you need a custom SSL hostname verifier, NTLM authentication, or a keep-alive strategy), define your own bean with the **exact same name** the library would register. The library uses `registerIfAbsent`, so it will skip any bean that already exists.

Example — disabling SSL hostname verification for a staging environment with internal CA certificates:

```java
@Configuration
@Profile("staging")
public class CustomClientConfig {

  @Bean
  public RestTemplate syncApiRestTemplate() {
    var tlsStrategy = ClientTlsStrategyBuilder.create()
        .setSslContext(SSLContexts.createDefault())
        .build();
    var connManager = PoolingHttpClientConnectionManagerBuilder.create()
        .setTlsSocketStrategy(tlsStrategy)
        .build();
    var httpClient = HttpClients.custom()
        .setConnectionManager(connManager)
        .build();
    var restTemplate = new RestTemplate(
        new HttpComponentsClientHttpRequestFactory(httpClient));
    restTemplate.setErrorHandler(new OpenTmfResponseErrorHandler());
    return restTemplate;
  }
}
```

The same approach works for `WebClient` (`{clientId}WebClient`) and `RestClient` (`{clientId}RestClient`) — define a bean with the exact name and the library will leave it untouched.

## Dynamic Token Caching

Bearer tokens are cached using [Caffeine](https://github.com/ben-manes/caffeine) with a per-token TTL derived from the `expires_in` field in the OAuth2 token response. This replaces the static `cache-expiry-seconds` from v1.x.

The cache TTL is computed as: `expires_in * cache-safety-factor` (default factor: `0.9`). If the token response does not include an `expires_in` field, the configurable `fallback-expires-in-seconds` is used (default: `3600`).

| Token `expires_in` | Cache TTL | Safety margin |
|---|---|---|
| 3600s (1 hour) | 3240s (54 min) | 6 min |
| 600s (10 min) | 540s (9 min) | 1 min |
| 180s (3 min) | 162s (2:42) | 18s |

Most users never need to configure `cache-safety-factor` — the default is sensible. Override only if you need tighter or looser margins.

## Mutual TLS Support

Both reactive and REST clients support Mutual TLS authentication through configuration. At least a key store must be provided in the `certificates` block. mTLS can be used together with a forward proxy (HTTP CONNECT tunneling) — the TLS handshake happens end-to-end through the tunnel.

```yaml
opentmf:
  http-clients:
    mtlsClient:
      bearer-auth:
        token-url: https://auth.example.com/token
        form-data:
          grant_type: client_credentials
      certificates:
        key-store:
          password: mypassword
          pk-password: mypassword
          base64-jks: <BASE64_JKS_CONTENT>
        trust-store:
          password: mypassword
          base64-jks: <BASE64_JKS_CONTENT>
```

For detailed instructions on generating keystores and truststores, see [Mutual TLS Configuration](./README_MTLS.md).

## Retry Behavior

The library **does not automatically retry** your HTTP calls. Retry handling is intentionally opt-in and controlled at call sites — you decide which operations are safe to retry. Use retries only for idempotent operations, and be extra careful with `POST` unless the target endpoint is idempotent.

> **Note:** The only internal retry is on **bearer token retrieval** — when the library fetches an OAuth2 token, it retries using the `num-retries` and `retry-wait-duration` from the client's configuration. This is transparent to the caller.

Both `WebClientUtil` and `SyncClientUtil` filter retries to the following HTTP status codes:

| Code | Meaning |
|------|---------|
| 408 | Request Timeout — the server timed out waiting for the request |
| 429 | Too Many Requests — rate limit exceeded |
| 500 | Internal Server Error — generic server-side failure |
| 502 | Bad Gateway — upstream server returned an invalid response |
| 503 | Service Unavailable — server temporarily overloaded or in maintenance |
| 504 | Gateway Timeout — upstream server did not respond in time |
| 509 | Bandwidth Limit Exceeded — non-standard, used by some providers to signal throttling |

### Reactive clients

Chain `WebClientUtil.retry(...)` onto the reactive pipeline:

```java
webClient.get()
    .uri("/catalog")
    .retrieve()
    .bodyToMono(String.class)
    .retryWhen(WebClientUtil.retry(
        props.getNumRetries(),
        props.getRetryWaitDuration()));
```

### REST clients (RestClient and RestTemplate)

Wrap the call with `SyncClientUtil.executeWithRetry(...)`. This works identically with both `RestClient` and `RestTemplate`:

```java
// RestClient (recommended)
String result = SyncClientUtil.executeWithRetry(
    () -> restClient.get().uri("/catalog").retrieve().body(String.class),
    props.getNumRetries(),
    props.getRetryWaitDuration());

// RestTemplate
String result = SyncClientUtil.executeWithRetry(
    () -> restTemplate.getForObject("/catalog", String.class),
    props.getNumRetries(),
    props.getRetryWaitDuration());
```

Both methods use exponential backoff and accept an optional jitter factor.

### Error handling

All library-created clients (`WebClient`, `RestClient`, and `RestTemplate`) automatically convert HTTP error responses into `OpenTmfClientResponseException`. For 404 responses, the more specific `OpenTmfClientNotFoundException` is thrown. Both exception types carry the HTTP status code, a human-readable message, and the raw response body:

```java
try {
    restClient.get().uri("/catalog/123").retrieve().body(String.class);
} catch (OpenTmfClientNotFoundException e) {
    // 404 — resource not found
    log.info("Not found: {}", e.getMessage());
} catch (OpenTmfClientResponseException e) {
    // Any other HTTP error (4xx, 5xx)
    log.error("Status {}: {}", e.getRawStatusCode(), e.getMessage());
    String rawBody = e.getResponseBody(); // raw body for custom parsing
}
```

For reactive clients the same exceptions are thrown in the error channel:

```java
webClient.get().uri("/catalog/123")
    .retrieve()
    .bodyToMono(Catalog.class)
    .onErrorResume(OpenTmfClientNotFoundException.class, e -> Mono.empty())
```

The error message is intelligently extracted from the response body — the library recognizes RFC 7807 Problem Details, TMF Open API, OAuth2, Spring Boot, and generic JSON error formats, with safe fallbacks for plain text and binary bodies.

#### Handling 404 as empty result

For GET-by-ID patterns where 404 means "not found, return empty":

```java
// RestClient (recommended)
Optional<Catalog> catalog = SyncClientUtil.emptyOn404(
    () -> restClient.get().uri("/catalog/123").retrieve().body(Catalog.class));

// RestTemplate
Optional<Catalog> catalog = SyncClientUtil.emptyOn404(
    () -> restTemplate.getForObject("/catalog/123", Catalog.class));

// WebClient
Mono<Catalog> catalog = webClient.get().uri("/catalog/123")
    .retrieve()
    .bodyToMono(Catalog.class)
    .transform(WebClientUtil.emptyOn404());
```

A generalized `emptyOn(HttpStatus...)` variant is available for other status codes (e.g. 410 Gone):

```java
Optional<Catalog> catalog = SyncClientUtil.emptyOn(
    () -> restClient.get().uri("/catalog/123").retrieve().body(Catalog.class),
    HttpStatus.NOT_FOUND, HttpStatus.GONE);
```

#### Remapping to custom exception types

Consumer libraries can remap the auto-wrapped exception to a domain-specific subclass:

```java
// RestTemplate
try {
    restTemplate.getForObject("/catalog/123", String.class);
} catch (OpenTmfClientResponseException e) {
    throw HttpClientUtil.remap(e, MyCatalogException.class);
}

// WebClient
webClient.get().uri("/catalog/123")
    .retrieve()
    .bodyToMono(String.class)
    .onErrorMap(OpenTmfClientResponseException.class,
        e -> HttpClientUtil.remap(e, MyCatalogException.class))
```

The target exception class must extend `OpenTmfClientResponseException` and provide a `(HttpStatusCode, String, String)` or `(HttpStatusCode, String)` constructor.

#### Legacy manual error handling

The `handleError(...)` methods on `WebClientUtil` and `SyncClientUtil` remain available for use with non-library-created clients or for backward compatibility.

### Shared utilities

`SyncClientUtil` works with both `RestClient` and `RestTemplate` — all its methods (`executeWithRetry`, `emptyOn404`, `emptyOn`, `shouldRetryOn`, `handleError`) operate on the shared exception hierarchy (`RestClientResponseException`, `OpenTmfClientResponseException`) rather than on client-specific APIs.

`HttpClientUtil` exposes the shared retryable-status logic (`isRetryableStatus`, `createException`, `remap`) used by both `WebClientUtil` and `SyncClientUtil`.

## Resilience (circuit breaker & bulkhead)

The library can optionally decorate every client with [resilience4j](https://resilience4j.readme.io/): a **circuit breaker** (stop hammering a degraded remote), a **bulkhead** (bound concurrent calls), and — for reactive clients — a **time limiter**. It is **off by default** and fully optional: without the resilience4j jars on the classpath, or without `resilience.enabled: true`, nothing changes.

Add the dependencies (versions are yours to manage; the library compiles against 2.x):

```xml
<dependency>
  <groupId>io.github.resilience4j</groupId>
  <artifactId>resilience4j-circuitbreaker</artifactId>
</dependency>
<dependency>
  <groupId>io.github.resilience4j</groupId>
  <artifactId>resilience4j-bulkhead</artifactId>
</dependency>
<!-- netty clients additionally need: -->
<dependency>
  <groupId>io.github.resilience4j</groupId>
  <artifactId>resilience4j-reactor</artifactId>
</dependency>
<!-- optional, for resilience4j.circuitbreaker.* metrics: -->
<dependency>
  <groupId>io.github.resilience4j</groupId>
  <artifactId>resilience4j-micrometer</artifactId>
</dependency>
```

Then enable per client:

```yaml
opentmf:
  http-clients:
    onedms:
      base-url: https://onedms.example.com
      resilience:
        enabled: true                       # default false
        circuit-breaker:
          failure-rate-threshold: 50        # %, default 50
          slow-call-rate-threshold: 100     # %, default 100
          slow-call-duration-threshold: 5s
          sliding-window-size: 50
          minimum-number-of-calls: 20
          wait-duration-in-open-state: 30s
          permitted-calls-in-half-open: 5
          record-status-codes: [500, 502, 503, 504]
        bulkhead:
          max-concurrent-calls: 25          # 0 (default) = bulkhead disabled
          max-wait-duration: 0s
        time-limiter:                       # netty clients only
          timeout-duration: 10s             # unset (default) = disabled
```

**Semantics:**

- **One configuration per client id, applied to every shape of that id** — `<id>RestTemplate`, `<id>RestClient` or `<id>WebClient`, *and* the client's bearer-token calls. A broken identity provider opens the same circuit as the API itself, preventing token-endpoint stampedes.
- **Order:** bulkhead → circuit breaker → (time limit) → HTTP call. Your `executeWithRetry` / `WebClientUtil.retry` calls sit *outside* all of it.
- **What trips the breaker:** connection errors, timeouts, and responses whose status is in `record-status-codes`. 4xx responses never do — they indicate caller bugs, not a degraded remote. 404 stays a normal outcome (`emptyOn404` unaffected).
- **Rejected calls** (open circuit, full bulkhead) throw `OpenTmfClientResilienceException` — *not* part of the `OpenTmfClientResponseException` hierarchy, so the retry utilities never retry them: an open circuit means "stop calling". Map it to `503 Service Unavailable` in your error advice if you expose the failure upstream.
- **Metrics:** with a `MeterRegistry` bean and `resilience4j-micrometer` present, `resilience4j.circuitbreaker.*` and `resilience4j.bulkhead.*` meters (tagged `name=<clientId>`) appear on the standard scrape automatically.

## Dynamic clients (`HttpClientRegistry`)

The static `opentmf.http-clients.*` clients are create-once Spring beans — the right lifecycle for a fixed set of dependencies, the wrong one for clients built **programmatically at runtime** (e.g. from catalog rows that SREs change without a redeploy). For those, autowire the `HttpClientRegistry` bean:

```java
@Autowired HttpClientRegistry registry;

// build (or fetch) a client for a source row
var client = registry.getOrCreate("onedms", ClientType.APACHE, props);
client.restClient().get().uri("/things").retrieve().body(String.class);
client.tokenService().getToken();          // matches the row's auth config

// the row changed → hot-swap; the old client closes after a 30s grace period
registry.replace("onedms", ClientType.APACHE, newProps);

// the row was deleted
registry.evict("onedms");

// reactive sources
var reactive = registry.getOrCreateReactive("asgw", props);
reactive.webClient(); reactive.tokenService();
```

Semantics:

- **Grace-period close** — `replace`/`evict` never yank a client out from under in-flight requests: the retired client closes 30 seconds later. Closing is per-type: Apache closes its pooled connection manager gracefully; JDK uses a guarded `AutoCloseable` close (real close on Java 21+ runtimes, GC-reclaimed no-op on 17); Netty disposes the client's (and its token client's) `ConnectionProvider` gracefully.
- **Resilience reset** — `replace`/`evict` also reset the name's resilience4j instances, so a re-created client starts with breaker state and configuration built from its current properties.
- **Pool gauges** — with a `MeterRegistry` bean, every Apache client (static or dynamic) exposes `opentmf.client.pool.leased|available|pending|max{client=<name>}`; `evict` removes the gauges.
- Prefer `client-type: apache` for dynamic sources: real pool control and pool metrics. On the JDK type, `max-connections` is unenforceable (see below).

### Validating a configuration (`ClientPropertiesValidator`)

For "test connection" flows and health checks, validate a programmatically built configuration without side effects — all findings are returned at once, suitable for a UI checklist:

```java
List<ClientPropertiesValidator.Finding> findings =
    ClientPropertiesValidator.validate(ClientType.APACHE, props);
```

Checks include per-auth-mode required fields, base64-decodability of mTLS material, sane timeouts, proxy settings, resilience ranges — and flags **`max-connections` on the JDK client type**, which has no pool-size API: use `client-type: apache`, or enforce the concurrency contract with `resilience.bulkhead.max-concurrent-calls`.

## Migration from v1.x

This project replaces `opentmf-web-clients` (v1.x). The key changes are:

### Maven dependency

**Before** (v1.x — pick one or both):

```xml
<dependency>
  <groupId>org.opentmf.client</groupId>
  <artifactId>opentmf-basic-webclients-starter</artifactId>
</dependency>
<dependency>
  <groupId>org.opentmf.client</groupId>
  <artifactId>opentmf-openid-webclients-starter</artifactId>
</dependency>
```

**After** (v2.x — pick the starter that fits your stack):

```xml
<!-- Umbrella (REST + reactive) -->
<dependency>
  <groupId>org.opentmf.client</groupId>
  <artifactId>opentmf-http-clients-starter</artifactId>
  <type>pom</type>
</dependency>

<!-- Or REST-only / reactive-only -->
<dependency>
  <groupId>org.opentmf.client</groupId>
  <artifactId>opentmf-http-clients-starter-rest</artifactId>
  <type>pom</type>
</dependency>
```

### YAML configuration

The two separate maps (`opentmf.webclient.basic` and `opentmf.webclient.openid`) are unified into a single `opentmf.http-clients` map.

**Before:**

```yaml
opentmf:
  webclient:
    basic:
      myBasic:
        connection-provider-name: myBasic
        token-config:
          username: admin
          password: secret
    openid:
      myOpenId:
        connection-provider-name: myOpenId
        token-config:
          token-url: https://auth.example.com/token
          basicAuthUsername: user
          basicAuthPassword: pass
          cache-expiry-seconds: 300
          form-data:
            grant_type: client_credentials
```

**After:**

```yaml
opentmf:
  http-clients:
    myBasic:
      basic-auth:
        username: admin
        password: secret
    myOpenId:
      bearer-auth:
        token-url: https://auth.example.com/token
        client-id: user
        client-secret: pass
        form-data:
          grant_type: client_credentials
```

### Property mapping

| v1.x | v2.x | Notes |
|---|---|---|
| `opentmf.webclient.basic.<id>.*` | `opentmf.http-clients.<id>.*` | Moved out of `basic:` block |
| `opentmf.webclient.openid.<id>.*` | `opentmf.http-clients.<id>.*` | Moved out of `openid:` block |
| `connection-provider-name` | *(removed)* | Derived from the map key |
| `token-config:` (basic) | `basic-auth:` | Renamed |
| `token-config:` (openid) | `bearer-auth:` | Renamed |
| `token-config.basicAuthUsername` | `bearer-auth.client-id` | Renamed for OAuth2 clarity |
| `token-config.basicAuthPassword` | `bearer-auth.client-secret` | Renamed for OAuth2 clarity |
| `token-config.cache-expiry-seconds` | *(removed)* | Now dynamic from token `expires_in` |
| *(n/a)* | `bearer-auth.cache-safety-factor` | Optional, default 0.9 |
| *(n/a)* | `opentmf.client-type` | Global default: `jdk`, `apache`, or `netty` |

### Java import changes

```
// Before
import org.opentmf.client.common.model.BaseClientProperties;
// After
import org.opentmf.client.common.model.ClientProperties;

// Before
import org.opentmf.client.common.exception.OpenTmfWebClientException;
// After
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
```

### Environment variable migration

| v1.x pattern | v2.x pattern |
|---|---|
| `OPENTMF_WEBCLIENT_BASIC_<ID>_*` | `OPENTMF_HTTP_CLIENTS_<ID>_*` |
| `OPENTMF_WEBCLIENT_OPENID_<ID>_*` | `OPENTMF_HTTP_CLIENTS_<ID>_*` |
| `*_CONNECTION_PROVIDER_NAME` | *(removed)* |
| `*_TOKEN_CONFIG_USERNAME` | `*_BASIC_AUTH_USERNAME` |
| `*_TOKEN_CONFIG_PASSWORD` | `*_BASIC_AUTH_PASSWORD` |
| `*_TOKEN_CONFIG_TOKEN_URL` | `*_BEARER_AUTH_TOKEN_URL` |
| `*_TOKEN_CONFIG_BASIC_AUTH_USERNAME` | `*_BEARER_AUTH_CLIENT_ID` |
| `*_TOKEN_CONFIG_BASIC_AUTH_PASSWORD` | `*_BEARER_AUTH_CLIENT_SECRET` |
| `*_TOKEN_CONFIG_CACHE_EXPIRY_SECONDS` | *(removed)* |
| `*_TOKEN_CONFIG_FORM_DATA_*` | `*_BEARER_AUTH_FORM_DATA_*` |

## License

[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)
