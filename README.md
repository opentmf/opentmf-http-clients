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
| `response-timeout` | Duration | `45s` | How long to wait for the answer: one deadline for the whole exchange on `jdk`, silence per read on `apache` and `netty` — see below |
| `exchange-timeout` | Duration | *(none)* | Opt-in bound on the whole exchange, from the send to the last byte of the body, on every client type — see below |
| `connection-idle-timeout` | Duration | `4m` | How long idle connections stay in the pool before eviction |
| `num-retries` | int | `3` | Maximum retry count (used by `executeWithRetry` / `WebClientUtil.retry`) |
| `retry-wait-duration` | Duration | `5s` | Base wait between retries (exponential backoff) |
| `max-retry-after` | Duration | `30s` | Longest server-requested `Retry-After` to honor; beyond it the call fails fast instead of waiting |
| `follow-redirects` | boolean | `true` | Automatically follow HTTP 3xx redirects |
| `ssl-protocol` | string | `TLS` | SSL/TLS protocol version (`TLS`, `TLSv1.2`, `TLSv1.3`) |
| `logging-enabled` | boolean | `true` | Enable Logbook request/response logging for this client |
| `compression-enabled` | boolean | `true` | Enable HTTP response compression (gzip) |
| `fixed-headers` | map | *(none)* | Headers added to every API request as defaults (a per-request value wins). Never sent on the token call — see [Headers on the token call](#headers-on-the-token-call-bearer-authheaders) |
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

> **What `response-timeout` bounds per client type** (the same holds for `bearer-auth.response-timeout` on the token call):
> - **JDK** (`client-type: jdk`): **one deadline for the whole exchange**, counted from the moment the request is sent: the wait for the headers and the reading of the body both count against it (Spring's `JdkClientHttpRequest` closes the body stream when the deadline passes). A peer that sends its headers and then stalls is cut at `response-timeout` after the send — and so is a body that is still arriving, however steadily: a download that legitimately takes longer than `response-timeout` fails. Set `response-timeout` above the longest whole call you expect, downloads included.
> - **Apache** (`client-type: apache`) and **Netty** (`client-type: netty`): **silence per read**. Each wait for data, for the headers and for every part of the body, may last up to `response-timeout`, and the clock starts again with every byte that arrives. A peer that stalls is cut after `response-timeout` without a byte; a body that keeps arriving, however slowly, is never cut, so a long download is not limited, and neither is a peer that trickles a byte just inside the timeout.
>
> What the caller sees when a body is cut (measured, `ResponseTimeoutIT`):
> - **JDK:** a `RestClientException` ("Error while extracting response for type […]") caused by an `IOException` (on JDK 17: `closed`, caused by `fixed content-length: 64, bytes received: 8`). The same with Logbook writing the call: Logbook's own read of the body fails first, is logged as a WARN ("Unable to log response") and dropped, and the caller's read then fails the same way.
> - **Apache:** a `RestClientException` ("Error while extracting response for type […]") caused by a `SocketTimeoutException` ("Read timed out"), with Logbook writing the call or not. (Logbook reads the body inside the exchange to log it, and drops a failure of that read with a WARN, "Unable to log response"; the library's Logbook interceptor raises that same failure when the caller reads the body. Up to 2.5.1 the caller found the end of the stream instead, so on `apache` with Logbook at `TRACE` a body cut by the timeout came back as a `200` without a body.)
> - **Netty:** a `WebClientResponseException` caused by Netty's `ReadTimeoutException`, with Logbook writing or not ("200 OK from GET … but response failed with cause: io.netty.handler.timeout.ReadTimeoutException").
>
> **On `netty` a cut body arrives as a `WebClientResponseException` that carries the answer's own status, a `200`:** code that reads that exception as "the server answered with an error" misreads a timeout. Tell them apart by the cause (`ReadTimeoutException`) or by `getStatusCode().isError()`, never by the exception type alone. With `exchange-timeout` set (below), a cut body arrives as a `WebClientRequestException` instead.
>
> **A caller that must bound a whole call:** set `exchange-timeout` (below). Without it, on `jdk` `response-timeout` already is that bound; on `netty` the Mono's own `.timeout(Duration)` on the call is one (it cancels the exchange and closes the connection when it passes); on `apache` there is none: a peer that keeps sending, however slowly, holds the call as long as it sends.

> **What `exchange-timeout` bounds** (opt-in, unset by default; a client that does not set it behaves exactly as before):
> - **The whole exchange**, from the moment the request is sent to the last byte of the body: the headers and the whole body must arrive within it. A body that keeps arriving is cut when it passes, however steadily it arrives. **It also cuts a slow but legitimate large download:** set it above the longest whole call you expect, downloads included.
> - **Apache** and **Netty:** a bound of its own beside `response-timeout` (silence per read): whichever passes first cuts the exchange. A peer that stalls is still cut by `response-timeout` when that comes first; a peer that trickles is cut by `exchange-timeout`.
> - **JDK:** `response-timeout` already is one deadline over the whole exchange; with both set, **the smaller of the two** is that deadline, because both mean the same thing there and the tighter promise is the one the caller asked for.
> - **The token call** runs under the client's `exchange-timeout` too (there is no `bearer-auth` key of its own). A mint cut by it is retried once at once, as any transport failure of a mint, so it holds a token retrieval for about twice `exchange-timeout`.
>
> What the caller sees when `exchange-timeout` cuts (measured, `ExchangeTimeoutIT`); never a status exception, so a cut exchange never passes as an answer:
> - **Apache:** a `RestClientException` ("Error while extracting response for type […]") when the body was cut, a `ResourceAccessException` when the headers had not arrived; either caused by a `SocketTimeoutException` ("exchange-timeout of 3000 ms passed before the response was fully received"), so code that handles a read timeout handles it the same way. The same with Logbook writing the call.
> - **Netty:** a `WebClientRequestException` caused by the same `SocketTimeoutException`, whether the headers or the body were cut, with Logbook writing or not. With `exchange-timeout` set, a body that fails for another transport reason (the `response-timeout` stall, a premature close) arrives as a `WebClientRequestException` too, caused by that failure, never as the `200` above.
> - **JDK:** the JDK client's own timeout, as for `response-timeout`: a `RestClientException` caused by an `IOException` when the body was cut, a `ResourceAccessException` caused by an `HttpTimeoutException` ("Request cancelled") when the headers had not arrived.
>
> **Which bound fired is not part of the contract.** The message of the `SocketTimeoutException` names the bound for a person reading a log; it is informational and not a contract to match on. A caller cannot tell from the exception whether `exchange-timeout` or `response-timeout` cut the exchange: both arrive as a timeout under the client's transport failure.

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
>
> **Token calls are logged without their response body.** A bearer client's token call goes through Logbook like any other call, the request (Logbook's default filters mask the form's `client_secret`, `password` and `refresh_token`), the status and the headers included, but the token endpoint's response body is never handed to Logbook: `<token response body withheld; bearer-auth.log-response-body: true logs it>` stands in its place. An identity provider may echo the request form, and a token answer holds the token itself. This holds on every client type and does not depend on, or change, your Logbook configuration; the client's own calls keep their bodies. `bearer-auth.log-response-body: true` brings the body back, for debugging a token endpoint.

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
        request-timeout: 5s             # the token call's own bounds; each defaults to the
        response-timeout: 10s           # client's own value, see "Token request bounds"
        num-retries: 1
        headers:                        # sent on the token call only
          x-api-key: myApiKey
        log-response-body: false        # true: Logbook shows the token answer; debugging only
        # failure-memory: 10s           # opt-in, unset by default: see "Concurrent callers and
                                        # a failing identity provider"
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
      resilience:                       # optional — requires resilience4j jars, see "Resilience"
        enabled: true
        circuit-breaker:
          failure-rate-threshold: 50
          sliding-window-size: 50
          minimum-number-of-calls: 20
          wait-duration-in-open-state: 30s
          record-status-codes: [500, 502, 503, 504]
        bulkhead:
          max-concurrent-calls: 25
        time-limiter:                   # netty clients only
          timeout-duration: 10s
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

### Application-scoped beans

Independent of the per-client beans, the starter registers these singletons:

| Bean Name | Type | Condition |
|---|---|---|
| `opentmfHttpClientRegistry` | `HttpClientRegistry` | always — see [Dynamic clients](#dynamic-clients-httpclientregistry) |
| `opentmfResilienceRegistries` | `ResilienceRegistries` | resilience4j on the classpath — see [Resilience](#resilience-circuit-breaker--bulkhead) |
| `opentmfResilienceMetricsBinder` | `ResilienceMetricsBinder` | resilience4j-micrometer + a `MeterRegistry` bean |
| `opentmfApachePoolMeters` | `ApachePoolMeters` | Apache HttpClient 5 + Micrometer + a `MeterRegistry` bean |
| `opentmfTokenFetchMeters` | `TokenFetchMeters` | Micrometer + a `MeterRegistry` bean — see [Token fetch](#token-fetch-transport-retry-typed-failure-metrics) |

You normally only autowire `HttpClientRegistry` (for dynamic clients); the others work behind the scenes — `ResilienceRegistries` is useful in tests or when you need direct access to a client's `CircuitBreaker`/`Bulkhead` instances.

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

#### An interceptor on every client (`OutboundInterceptorContribution`)

The routes above change one injected client. To put an interceptor on **every** synchronous client the library builds, declare an `OutboundInterceptorContribution` bean. It wraps a `ClientHttpRequestInterceptor` and an order:

```java
@Configuration
public class OutboundInterceptorConfig {

  @Bean
  public OutboundInterceptorContribution tenantHeader() {
    return new OutboundInterceptorContribution((request, body, execution) -> {
      request.getHeaders().set("X-Tenant", TenantContext.current());
      return execution.execute(request, body);
    }, 10);
  }
}
```

**Where it applies.** The library adds the contributions while it builds a client, before the `RestClient` copies the `RestTemplate`'s interceptor list. So they reach:

| Covered | Not covered |
|---|---|
| `{clientId}RestTemplate` and `{clientId}RestClient` (`jdk` and `apache`) | `{clientId}WebClient` (`netty`) and its token calls |
| the token calls of `{clientId}TokenService` (they run through the `RestClient`) | clients you build yourself (see [Replacing a client entirely](#replacing-a-client-entirely)) |
| sync clients built at runtime through [`HttpClientRegistry`](#dynamic-clients-httpclientregistry) | |

The library applies every contribution to every covered client. An interceptor that is meant for some clients only decides that itself, for example from `request.getURI()`.

**Order.** The contributions run **first**, ahead of all of the library's own interceptors: contributions → resilience → gzip (`jdk` only) → fixed headers → Logbook → HTTP call.

- Among themselves, the contributions run in ascending `order`. The one-argument constructor gives `Ordered.LOWEST_PRECEDENCE`, Spring's order for a bean that declares none. Contributions with the same order keep their bean registration order.
- As everywhere in Spring, an `@Order` on the `@Bean` method takes precedence over the `order` argument.
- A contribution sees the request before the fixed headers are filled in, so a header it sets wins over a `fixed-headers` default.

**Rejecting a call.** A contribution can refuse a call by throwing instead of calling `execution.execute(...)`. A `RuntimeException` reaches the caller unchanged, from the `RestTemplate`, the `RestClient` and the `TokenService` alike. Nothing goes on the wire. Because the contribution runs ahead of the resilience interceptor, the refused call does not count against the circuit breaker and takes no bulkhead permit.

**Wiring caveat.** The library looks up the contribution beans each time it builds a client: at startup for the `opentmf.http-clients.*` clients, at registration for the dynamic ones. Declare contributions in a configuration class that does not itself inject the library's client beans, or the two depend on each other.

Without an `OutboundInterceptorContribution` bean, the clients are built exactly as before.

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

### Headers on the token call (`bearer-auth.headers`)

A client's `fixed-headers` belong to its **API calls**. Since 2.5.0 they are never sent to the
token endpoint, on every client type (`jdk`, `apache`, `netty`) and for dynamic clients too: an
API key meant for the backend does not travel to the identity provider. The token call carries only
its own protocol headers — `Content-Type: application/x-www-form-urlencoded`, the Basic
`Authorization` when `client-id` and `client-secret` are set and, on `netty`, `Accept:
application/json` — plus whatever `bearer-auth.headers` names.

`bearer-auth.headers` (name → value) is the opt-in for an identity provider that needs a header of
its own. Those headers go on the token call **only**, never on the API calls, and they cannot
replace the protocol headers above.

Example — an API gateway (DXL on Apigee at VFDE) that wants its `x-api-key` on every service call.
Whether its token endpoint wants the key as well is the gateway's to answer; the map is the switch:

```yaml
opentmf:
  http-clients:
    backend:
      base-url: https://dxl.example.invalid/tobi/v1
      fixed-headers:
        x-api-key: my-consumer-key       # every API call
      bearer-auth:
        token-url: https://dxl.example.invalid/tobi/v1/token
        client-id: my-consumer-key
        client-secret: my-consumer-secret
        form-data:
          grant_type: client_credentials
        headers:
          x-api-key: my-consumer-key     # the token call too — only if the gateway needs it there
```

Before 2.5.0 the `fixed-headers` reached the token call as well; a client whose token endpoint
relied on that adds the same header under `bearer-auth.headers`.

### Token fetch: transport retry, typed failure, metrics

A cache miss *mints* a token: one `POST` to `token-url`, through the same client (so the same
interceptors, resilience decoration and observation) as the API calls — all but the client's
`fixed-headers`, which never reach the token endpoint (see
[Headers on the token call](#headers-on-the-token-call-bearer-authheaders)). A mint is idempotent,
and the library treats it that way:

- **Retry once on a transport-level failure.** If the connection dies under the mint — a keep-alive
  connection reused after the peer had already closed it, a reset, a premature EOF, an I/O timeout —
  the token client retries **exactly once**, immediately, then gives up. This covers both the
  headers-stage failure (`ResourceAccessException`) and the body-stage one, where the status line
  and headers arrived and the body then failed (`IOException: closed` from the JDK HttpClient under
  Spring's body extractor). The JDK HttpClient retries a stale pooled connection by itself only for
  `GET`/`HEAD`; the token mint is a `POST`, which is why the library owns this retry. Status errors
  from the token endpoint (`401 invalid_client`, `503`) are **not** retried here (retryable statuses
  have their own retry, below), and an open circuit breaker is never retried. The retry logs one
  `WARN` carrying the whole cause chain, outermost first
  (`Bearer token fetch from <url> failed at the transport level (RestClientException: …; caused by
  IOException: closed; caused by EOFException: …); retrying once`), so the log alone tells a
  premature EOF from a reset or an HTTP/2 `GOAWAY`.
- **Typed failure.** When the retry fails too, the mint throws
  `org.opentmf.client.bearer.exception.BearerTokenTransportException` (`tokenUrl`, `attempts`,
  cause = the last failure; the message carries the same cause chain as the `WARN`). It is deliberately *not* an `OpenTmfClientResponseException` — there is
  no HTTP status to report — so the retry utilities never retry it further. Map it to **503**
  (identity provider unreachable) in your error advice; a `BearerTokenException` (below) means the
  identity provider *answered* with an error and is typically mapped to **502**.
- **An error status from the token endpoint** — any 4xx or 5xx, a `404` included — throws
  `org.opentmf.client.bearer.exception.BearerTokenException`, on every client type (`jdk`,
  `apache`, `netty`), static and dynamic clients alike. It is an `OpenTmfClientResponseException`,
  so a catch of that type behaves as for any answered call; **catch `BearerTokenException` (and
  its sibling `BearerTokenResponseException`, below) first** to tell a failed token call from a
  failed call to the service the token was for — a token
  endpoint's `400`, `404` or `410` is not that service's answer. It carries `tokenUrl`, the status,
  the response headers, a parsed `Retry-After`, and `oauthError`, the OAuth 2.0 error code of the
  answer (RFC 6749 §5.2 `error`, e.g. `invalid_client`; `null` unless it is a lower-case code of
  letters, digits and underscores of at most 64 characters):
  `Bearer token request to <token-url> answered 401 Unauthorized (error=invalid_client)`. The
  identity provider's **body is withheld** — `getResponseBody()` is `null`, the message never
  quotes it, no cause carries it, no log line of the library prints it — because a provider may
  echo the request form, which can hold the client secret, a password or a refresh token;
  `error_description` is withheld with it. A `404` is this type, not
  `OpenTmfClientNotFoundException`: it says the token URL is wrong, not that a resource is absent.
  Retryable statuses are retried exactly as before (below).
- **A success answer without a usable token** — an empty body (`204` included), a body that is not
  a JSON object, or one whose `token-field` is missing, `null`, not a string or blank — throws
  `org.opentmf.client.bearer.exception.BearerTokenResponseException`, an
  `OpenTmfClientResponseException` carrying `tokenUrl`, the status, `tokenField` and a `reason`
  (`EMPTY_BODY` | `NOT_A_JSON_OBJECT` | `FIELD_MISSING` | `FIELD_NOT_A_STRING` | `FIELD_BLANK`):
  `Bearer token response from <token-url> (200 OK) has no 'access_token' field; expected a JSON
  object with a 'access_token' string`. The body never appears — not in the message, not in a
  chained cause, not in `getResponseBody()` — since it may hold other credentials. It is not
  retried and nothing is cached. Its status is the *success* status the endpoint sent, so map it on
  its type (typically to **502**, like any unusable identity-provider answer), not on its status.
- **One `INFO` line per mint** on success:
  `Bearer token minted from <token-url> (scope: [..], attempt <n>, <ms> ms)`. The URL names the
  issuer host and realm; form data (credentials) never appears in the log.
- **Counter** `opentmf.client.token.fetch{client=<id>, outcome=ok|retried|failed}` (Prometheus:
  `opentmf_client_token_fetch_total`) with a `MeterRegistry` bean present — one increment per mint,
  static and dynamic clients alike; cache hits are not counted. `ok` = first attempt succeeded,
  `retried` = a later attempt did, `failed` = the mint threw (any cause). Without a `MeterRegistry`
  bean nothing is registered. The same signal reaches code through
  `org.opentmf.client.bearer.observe.TokenFetchListener`, the extra constructor argument of
  `SyncTokenClientImpl` / `BearerTokenClientImpl` — useful when wiring the token clients by hand.

Token clients, sync and reactive alike, additionally retry retryable **statuses** per
`num-retries` (or `bearer-auth.num-retries`, see below) / `retry-wait-duration` /
`max-retry-after` (see [Retry Behavior](#retry-behavior)); each of those attempts owns its own
single transport retry. `num-retries: 0` turns the status retry off. When wiring
`SyncTokenClientImpl` by hand, pass the `ClientProperties` to get it — the constructors without
them keep the transport retry only.

### A failed token call since 2.5.2: what changes for users of 2.5.1

Measured on the released 2.5.1 jars and on 2.5.2, with the same cells: a client of each type
(`jdk`, `apache`, `netty`) whose token endpoint answers `400`, `404`, `410` or `503` with a JSON
body holding `error`, `error_description` and an echo of a credential.

| | 2.5.1 (released) | 2.5.2 |
|---|---|---|
| type, `400` / `410` / `503` | `OpenTmfClientResponseException` | `BearerTokenException` (an `OpenTmfClientResponseException`) |
| type, `404` | `OpenTmfClientNotFoundException` | `BearerTokenException` — **no longer** an `OpenTmfClientNotFoundException` |
| `getResponseBody()` | the whole body, the echo included | `null` |
| message | `HTTP 400 Bad Request: <error_description>` | `Bearer token request to <token-url> answered 400 Bad Request (error=invalid_client)` |
| status, headers, `Retry-After` | kept | kept, the same |
| token requests made | `503`: 1 + `num-retries`; others: 1 | the same |

What to check when upgrading:

- **A catch of `OpenTmfClientResponseException`** catches a failed token call exactly as before.
  Code that read the token endpoint's body from it (`getResponseBody()`, or the description in
  `getMessage()`) gets `null` and the new message; use `getOauthError()` for the error code.
- **A token endpoint's `404`** no longer reaches a catch of `OpenTmfClientNotFoundException` (or a
  `404` mapped to "not found" by type): it is a misconfigured token URL, not an absent resource.
  Code that maps a `404` by its *status* (`getRawStatusCode() == 404`) still sees `404`; catch
  `BearerTokenException` ahead of it.
- **A catch of `BearerTokenException`** now fires for every error status from the token
  endpoint, on every client type; until 2.5.2 a failed token call raised it only on a reactive
  token client built by hand over a `WebClient` without the library's error filter (there its
  message held the whole body; now it is the message above).
- **Every token call that got an answer and failed is one of two types**, both
  `OpenTmfClientResponseException`s and siblings, as in 2.5.1: `BearerTokenException` (an error
  status) and `BearerTokenResponseException` (a success status without a usable token). A consumer
  that tells a failed token call from the target's answer names both;
  `BearerTokenTransportException` (no answer) stays outside the family.
- **A client with `bearer-auth` but no `bearer-auth.token-url`** is refused when it is built —
  at context start, and by the dynamic-client registry — with
  `Client '<id>' has bearer-auth but no bearer-auth.token-url. …`. A `bearer-auth` block is bound
  as soon as any of its keys is set, so a sub-key alone (`bearer-auth.num-retries: 0` in a jar's
  `application.yml`) makes a bearer client. With a bean validator on the classpath such a client
  already failed at start (`BindValidationException` on `bearerAuth.tokenUrl`); without one it was
  built and failed at its first token call (a `NullPointerException`). `bearer-auth.use-mock: true`
  needs no token URL and is accepted as before.
- **A `bearer-auth.token-url` that is not an absolute `http` or `https` URL with a host** — a
  misspelt scheme (`htps://…`), a space in the host, an empty host (`http:///token`) — is refused
  in the same places, by name and without quoting the URL:
  `Client '<id>' has a bearer-auth.token-url that is not an absolute http or https URL with a
  host.` On 2.5.1 such a client started and failed at its first token call: an untyped
  `IllegalArgumentException` on `jdk`, a `BearerTokenTransportException` after a second, useless
  attempt on `apache` and `netty` (measured).
- **A user part of the token URL is no longer shown.** A token URL such as
  `http://user:password@idp/token` is accepted as before and works where it worked (`jdk`, `netty`:
  no client type sends the part; `apache` refuses such a URL at the call, as before), but the
  library never shows the part: not in its log lines (the `INFO` of a minted token, the `DEBUG`
  before a fetch, the transport `WARN`), not in the messages or `getTokenUrl()` of
  `BearerTokenException`, `BearerTokenResponseException` and `BearerTokenTransportException`, not
  to a `TokenFetchListener`, not in a stack trace (with such a URL a
  `BearerTokenTransportException` chains no cause, which would quote the URL; its message keeps
  the cause chain without the part). On 2.5.1 the `INFO` line of every mint printed it
  (measured). Put a credential in `client-id` / `client-secret` or `form-data`, never in the URL.
- **Logbook's TRACE line of a token call no longer shows the response body** (when Logbook is on
  the classpath and `logging-enabled`, the default): a marker stands in its place; the request,
  the status and the headers of the line are unchanged, and the client's own calls keep their
  bodies. On 2.5.1 the line carried the whole answer through Logbook's own filters, which mask a
  JSON answer's tokens but not, for example, an echoed `client_secret` (measured on all three
  client types, a `400` and a `200` echoing it). `bearer-auth.log-response-body: true` restores
  the 2.5.1 line.

### Token request bounds (`bearer-auth.request-timeout` / `response-timeout` / `num-retries`)

By default a mint runs on the **client's own** `request-timeout`, `response-timeout` and
`num-retries` — the bounds you tuned for the backend. For a slow backend that is the wrong bound
for the identity provider, which answers in seconds: a client with `response-timeout: 120s` and
`num-retries: 3` waits up to 120 s per token try, twice per attempt (the transport retry above) —
about 240 s for an identity provider that does not answer, and up to four such attempts (about
16 minutes) for one that answers `503` slowly, all inside one business call on a cold token
cache. And a client cannot simply lower `num-retries` for the mint, because the same
key is the value its business calls opt into.

Three keys under `bearer-auth` give the token request bounds of its own:

| Key | Default | The token request's own… |
|---|---|---|
| `bearer-auth.request-timeout` | the client's `request-timeout` | time allowed for obtaining a usable connection (same meaning per client type as the client's key) |
| `bearer-auth.response-timeout` | the client's `response-timeout` | time to wait for the token endpoint's answer (same meaning per client type as the client's key: [one deadline on `jdk`, silence per read on `apache` and `netty`](#per-client-properties)) |
| `bearer-auth.num-retries` | the client's `num-retries` | number of retries of a retryable status (`503`, `429`, …) |

Each one, when unset, is the client's own value, so a client that sets none of them behaves
exactly as before. The client's `exchange-timeout`, when set, bounds the token request
as well (see [What `exchange-timeout` bounds](#per-client-properties)); there is no `bearer-auth`
key of its own for it. The client's own API calls keep the client's bounds whatever is set here. The
token request still shares the client's connection pool, TLS, proxy, interceptors and resilience
decoration; only its bounds differ. (On `jdk`, `bearer-auth.request-timeout` is the JDK
client's connect timeout, which only an `HttpClient` of its own can carry: the token calls then
get a companion client with the same settings, closed together with the client's own.)

**What to set:** a bound that fits your identity provider, typically a few seconds each — for
example:

```yaml
opentmf:
  http-clients:
    archive:                          # a slow backend
      response-timeout: 120s
      num-retries: 3                  # the archive calls' own retries
      bearer-auth:
        token-url: https://idp.example.invalid/token
        # ...
        request-timeout: 5s
        response-timeout: 10s         # an identity provider that does not answer: ~20 s, not ~240 s
        num-retries: 1
```

Keep in mind that a mint that times out is retried **once** at once (the transport retry above), so
an identity provider that does not answer holds a mint for about twice
`bearer-auth.response-timeout` per status attempt.

### Concurrent callers and a failing identity provider

**Why a cache alone does not stop the repeat.** A token is cached only once its call succeeded,
and finding it, calling for it and storing it are three steps: every caller that finds no token
calls. That happens on a cold cache, every time a cached token expires under load, and all the
while an identity provider is failing, when nothing is ever stored. Until 2.5.2 each of those
callers made its own token call, with its own retries. Measured at a stand-in token endpoint, K
concurrent callers of one key, sync and reactive alike:

| Case | Token calls until 2.5.1, K = 1 / 8 / 32 | Since 2.5.2 |
|---|---|---|
| Outage: the token endpoint answers `503` (default `num-retries: 3`) | 4 / 32 / 128 | 4 / 4 / 4 |
| Outage: the connection fails (one transport retry) | 2 / 16 / 64 | 2 / 2 / 2 |
| Recovery: the endpoint answers again, the cache is empty | 1 / 8 / 32 | 1 / 1 / 1 |
| A cached token expires under load | 1 / 8 / 32 | 1 / 1 / 1 |

**One mint per key.** A caller that finds no cached token while a mint of its key (token URL,
scope, username) is in flight waits for that mint and shares its outcome, the token or the
failure. It needs no setting. What a caller sees of it:

- The token counter `opentmf.client.token.fetch` and the `Bearer token minted` line count once
  per burst of callers, not once per caller. They still count once per mint, as documented: there
  are fewer mints.
- The mint runs on the thread of the caller that started it (sync), or subscribed with that
  caller's Reactor context (reactive). Its HTTP call, its log lines (and their trace id) and the
  token call's span belong to **that caller's trace**. A caller that waited for it has no token
  call in its own trace: its wait shows only as time inside its own call. To find why a call was
  slow on a token, look for the mint in the trace of the call that started it, at the same moment.
- A waiting caller that fails gets its own copy of the failure: the same type, message, status,
  headers, `Retry-After` and cause, with its own stack trace. No exception instance is shared
  between callers.
- A reactive caller that cancels (a timeout operator, say) stops waiting; the mint is not
  cancelled with it, and its token is cached for the next caller.
- A failure is shared only by the callers waiting for that mint. Once it has settled, the next
  caller mints again, unless the failure memory below is on.

A single caller sees no change: same calls, outcomes, exceptions and listener notifications
(measured against 2.5.1, `docs/token-single-flight-compatibility.md`).

**`bearer-auth.failure-memory`: remembering a failure (opt-in).** Unset by default, and then
nothing is remembered. When set, a token retrieval that failed **retryably** after the token
call's own retries is remembered for that long. Until then every token request for the same key
fails at once with that failure (its own copy), without a call. Retryable means:

- a 5xx, `408` or `429` from the token endpoint;
- a transport failure (`BearerTokenTransportException`).

A `Retry-After` from the token endpoint lengthens the memory, up to the client's
`max-retry-after`. A refused credential (`400`, `401`, `403`, any other 4xx) and an unusable answer
are never remembered: they are the identity provider's verdict, not an outage. The library logs one
`WARN` when it remembers a failure, and a `DEBUG` line for each request it fails from the memory.
A failure served from the memory is no mint: the token counter does not count it. `clearCache()`
forgets every remembered failure. The cost is that, once the identity provider is back, the next
token can come up to `failure-memory` late, so keep it short (seconds, about one `retry-wait-duration`):

```yaml
opentmf:
  http-clients:
    orders:
      bearer-auth:
        token-url: https://idp.example.invalid/token
        # ...
        failure-memory: 10s           # an outage costs one retrieval per 10 s per key, not one per caller
```

**Jittered token retries.** The token call's status retries wait the backoff spread at random by
up to half of it either way. The token calls of keys and pods that failed together then do not
all retry at the same moment. The number of retries and the `Retry-After` handling are as before.

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

> **Note:** The only internal retries are on **bearer token retrieval**. Every token client (sync and reactive) retries a transport-level failure once — see [Token fetch](#token-fetch-transport-retry-typed-failure-metrics). Every token client additionally retries retryable statuses using the `num-retries`, `retry-wait-duration` and `max-retry-after` from the client's configuration. Both are transparent to the caller.

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

This is true of **all three backends**. `client-type: apache` used to additionally retry 429 and 503 once inside Apache HttpClient itself, even when you had not opted in; that transport-level retry is disabled as of 2.1.6, so the same `num-retries` now means the same thing everywhere.

### Honoring the server's `Retry-After`

When a retryable response carries `Retry-After`, the retry utilities honor it. The division of authority is:

| Concern | Who decides |
|---|---|
| How many attempts | **You** — `Retry-After` never changes the attempt budget |
| How long between attempts | **The server**, as a *floor* — it can ask you to be more patient than your backoff, never less |
| The upper bound | **You** — `max-retry-after` (default `30s`) |

If the server asks for longer than `max-retry-after`, the call fails immediately instead of waiting. Retrying early against a server that explicitly asked for room just earns another rejection, and an unbounded wait would park a pooled thread for as long as the server likes.

Both formats are supported — `Retry-After: 120` and `Retry-After: Wed, 05 Aug 2026 12:00:00 GMT`. A `Retry-After` on a status that is *not* in the table above is logged at WARN and ignored: honoring it would introduce a retry you never asked for.

```java
// Pass the configured bound explicitly to override the 30s default:
SyncClientUtil.executeWithRetry(
    () -> restClient.get().uri("/catalog").retrieve().body(String.class),
    props.getNumRetries(), props.getRetryWaitDuration(), 0.0d, props.getMaxRetryAfter());
```

On failures, `OpenTmfClientResponseException` exposes `getRetryAfter()` and `getHeaders()` — the response headers as captured at the moment of the error, which is the only point they are still reachable. (On success, read headers yourself from `ResponseEntity`; the library does not intercept them.)

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
- **Order:** bulkhead → circuit breaker → (time limit) → HTTP call. Your `executeWithRetry` / `WebClientUtil.retry` calls sit *outside* all of it. On sync clients, your [`OutboundInterceptorContribution`s](#an-interceptor-on-every-client-outboundinterceptorcontribution) run before the bulkhead.
- **What trips the breaker:** connection errors, timeouts, and responses whose status is in `record-status-codes`. 4xx responses never do — they indicate caller bugs, not a degraded remote. 404 stays a normal outcome (`emptyOn404` unaffected).
- **Rejected calls** (open circuit, full bulkhead) throw `OpenTmfClientResilienceException` — *not* part of the `OpenTmfClientResponseException` hierarchy, so the retry utilities never retry them: an open circuit means "stop calling". Map it to `503 Service Unavailable` in your error advice if you expose the failure upstream.
- **Metrics:** with a `MeterRegistry` bean and `resilience4j-micrometer` present, `resilience4j.circuitbreaker.*` and `resilience4j.bulkhead.*` meters (tagged `name=<clientId>`) appear on the standard scrape automatically.

## Observability (trace-context propagation)

Every library-built client — `<id>RestTemplate`, `<id>RestClient`, `<id>WebClient`, dynamic
clients from `HttpClientRegistry`, and the internal bearer-token clients — is wired to the
application's Micrometer `ObservationRegistry` bean when one exists. No configuration on the
library side; behaviour is decided entirely by what the consuming application runs:

| Consumer setup | Outbound effect |
|---|---|
| No `ObservationRegistry` bean | Nothing — clients behave exactly as before |
| `ObservationRegistry` (e.g. Actuator), no tracer | `http.client.requests` observation metrics; no headers added |
| Micrometer-tracing + a propagator (e.g. OTel/Brave) | W3C `traceparent` (and `tracestate`) emitted on every outbound request — the server-side trace continues across the HTTP hop |

The library adds no dependency for this: the observation API ships transitively with
`spring-web`, and the actual trace emission comes from the handlers the *application* registers
(micrometer-tracing's propagating handler). Token-endpoint calls propagate too, since they run
through the same clients.

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
- **Pool gauges** — with a `MeterRegistry` bean, every Apache client (static or dynamic) exposes `opentmf.client.pool.leased|available|pending|max{client=<name>}`; `evict` removes the gauges. Bearer clients of any type also count their mints on `opentmf.client.token.fetch{client=<name>,outcome}`.
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

## Contributing

Work goes to `develop` through a pull request.

- **Build locally with JDK 17 and Maven 3.9.x**; the build refuses other versions. `mvn clean
  verify` builds every module and runs the unit and integration tests and the per-module coverage
  checks. No Docker is needed.
- **CI:** GitHub Actions runs that same `mvn clean verify` on every pull request and every push to
  `develop` (`.github/workflows/maven-build.yml`), on GitHub-hosted runners, with no secrets and
  nothing published.
- **A pull request merges only when both are green:**
  - its CI run;
  - the maintainers' local gate, which adds the Sonar analysis (no new issues, coverage of new
    code) and, for a change of behaviour, a compatibility report measured against the released
    version (see `docs/`).

## License

[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt)
