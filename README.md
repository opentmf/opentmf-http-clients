# opentmf-http-clients

General-purpose HTTP client libraries for Spring Boot with Logbook integration, configurable connection properties, fixed headers, and token retrieval with implicit access token caching.

Supports both **reactive** (`WebClient`) and **synchronous** (`RestTemplate`) clients with pluggable HTTP implementations, unified under a single configuration model.

**Authentication types:**

1. **Bearer Auth** — OAuth2/OIDC bearer tokens with dynamic Caffeine-based caching
2. **Basic Auth** — username/password Basic authentication
3. **No Auth** — unauthenticated clients

> This project replaces [opentmf-web-clients](https://github.com/opentmf/opentmf-web-clients) (v1.x). See the [Migration from v1.x](#migration-from-v1x) section for upgrade instructions.

## Modules

| Module | Description |
|---|---|
| `opentmf-http-clients-common` | Shared models, exceptions, and utilities (reactor-free, RestTemplate-free) |
| `opentmf-http-clients-rest` | Synchronous (RestTemplate) interfaces and utilities |
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

**REST-only** (RestTemplate / JDK or Apache HttpClient):

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

For **REST** clients, add one HTTP client implementation:

```xml
<!-- Option A: Apache HttpClient 5 (recommended) -->
<dependency>
  <groupId>org.apache.httpcomponents.client5</groupId>
  <artifactId>httpclient5</artifactId>
</dependency>

<!-- Option B: JDK HttpClient — no extra dependency needed (Java 11+) -->
```

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
      max-connections: 100
      request-timeout-millis: 50000
      response-timeout-millis: 50000
      num-retries: 3
      retry-wait-millis: 5000
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
      request-timeout-millis: 50000
      response-timeout-millis: 50000
      num-retries: 3
      retry-wait-millis: 5000
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
      request-timeout-millis: 5000
      response-timeout-millis: 10000
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
| `{clientId}RestTemplate` | `RestTemplate` |
| `{clientId}TokenService` | `SyncTokenService` |

A single client produces **either** reactive beans **or** REST beans, never both. If you need both a `WebClient` and a `RestTemplate` for the same backend, declare two clients with different IDs (e.g. one with `client-type: netty` and one with `client-type: jdk`).

### Autowiring beans

When the field name matches the bean name exactly, Spring resolves it by name — no `@Qualifier` is needed:

```java
@RequiredArgsConstructor
public class MyCatalogService {

  private final ClientProperties reactiveApiClientProperties;
  private final WebClient reactiveApiWebClient;
  private final TokenService reactiveApiTokenService;

  private final RestTemplate syncApiRestTemplate;
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

> **Note:** The only internal retry is on **bearer token retrieval** — when the library fetches an OAuth2 token, it retries using the `num-retries` and `retry-wait-millis` from the client's configuration. This is transparent to the caller.

Both `WebClientUtil` and `RestTemplateUtil` filter retries to the following HTTP status codes:

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
        Duration.ofMillis(props.getRetryWaitMillis())));
```

### REST clients

Wrap the call with `RestTemplateUtil.executeWithRetry(...)`:

```java
String result = RestTemplateUtil.executeWithRetry(
    () -> restTemplate.getForObject("/catalog", String.class),
    props.getNumRetries(),
    Duration.ofMillis(props.getRetryWaitMillis()));
```

Both methods use exponential backoff and accept an optional jitter factor.

### Error handling

All library-created clients (both `WebClient` and `RestTemplate`) automatically convert HTTP error responses into `OpenTmfClientResponseException`. For 404 responses, the more specific `OpenTmfClientNotFoundException` is thrown. Both exception types carry the HTTP status code, a human-readable message, and the raw response body:

```java
try {
    restTemplate.getForObject("/catalog/123", String.class);
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
// RestTemplate
Optional<Catalog> catalog = RestTemplateUtil.emptyOn404(
    () -> restTemplate.getForObject("/catalog/123", Catalog.class));

// WebClient
Mono<Catalog> catalog = webClient.get().uri("/catalog/123")
    .retrieve()
    .bodyToMono(Catalog.class)
    .transform(WebClientUtil.emptyOn404());
```

A generalized `emptyOn(HttpStatus...)` variant is available for other status codes (e.g. 410 Gone):

```java
Optional<Catalog> catalog = RestTemplateUtil.emptyOn(
    () -> restTemplate.getForObject("/catalog/123", Catalog.class),
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

The `handleError(...)` methods on `WebClientUtil` and `RestTemplateUtil` remain available for use with non-library-created clients or for backward compatibility.

### Shared utilities

`HttpClientUtil` exposes the shared retryable-status logic (`isRetryableStatus`, `createException`, `remap`) used by both `WebClientUtil` and `RestTemplateUtil`.

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

**After** (v2.x — single starter):

```xml
<dependency>
  <groupId>org.opentmf.client</groupId>
  <artifactId>opentmf-http-clients-starter</artifactId>
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
| `OPENTMF_WEBCLIENT_BASIC_<ID>_*` | `OPENTMF_CLIENTS_<ID>_*` |
| `OPENTMF_WEBCLIENT_OPENID_<ID>_*` | `OPENTMF_CLIENTS_<ID>_*` |
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
