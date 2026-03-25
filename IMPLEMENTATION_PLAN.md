# Implementation Plan: Unified Configuration, Project Restructuring & REST Support

## Goal

Restructure `opentmf-web-clients` into a new project `opentmf-http-clients` (new repository) that:

1. **Unifies configuration**: All clients live in a single flat map, with auth type determined by the presence of a `basic-auth` or `bearer-auth` block (or neither for no auth).
2. **Supports both reactive and synchronous clients**: Each client specifies `client-type: reactive` (WebClient) or `client-type: rest` (RestTemplate) via a per-client property.
3. **Pluggable HTTP implementations for REST**: REST clients can use Apache HttpClient 5 or JDK HttpClient, configurable via a global default with per-client override.

## Current vs Target Configuration

### Current

```yaml
opentmf:
  webclient:
    basic:
      client1:
        connection-provider-name: client1
        token-config:
          username: admin
          password: secret
    openid:
      client2:
        connection-provider-name: client2
        token-config:
          token-url: https://auth.example.com/token
          cache-expiry-seconds: 300
          form-data:
            grant_type: client_credentials
```

### Target

```yaml
opentmf:
  http-client-type: jdk                   # Global default for REST clients (jdk | apache)
  clients:
    client1:                             # Map key = connection provider name
      client-type: reactive              # reactive (default) | rest
      basic-auth:
        username: admin
        password: secret

    client2:
      client-type: rest
      http-client-type: apache           # Per-client override of global default
      bearer-auth:
        token-url: https://auth.example.com/token
        form-data:
          grant_type: client_credentials

    client3:
      client-type: rest                  # Uses global http-client-type (apache)
      # No auth block = no Authorization header
```

**Key properties:**
- `connection-provider-name` is eliminated. The map key serves as the client identity and is passed as a parameter to methods that need it (connection pool naming, cache naming, bean registration).
- `client-type`: Per-client. `reactive` (default) creates a `WebClient` bean; `rest` creates a `RestTemplate` bean.
- `http-client-type`: Global default + per-client override. Only applies to `client-type: rest`. Options: `jdk` (JDK HttpClient), `apache` (Apache HttpClient 5). Ignored for reactive clients (always Reactor Netty).

## Current Module Structure

```
opentmf-web-clients/
├── opentmf-webclients-common/              # BaseClientProperties, WebClientConfigUtil, WebClientUtil, TokenService, WebClientProvider, WebClientProviderBaseImpl
├── opentmf-basic-webclient-provider/       # BasicClientProperties, BasicTokenProperties, BasicTokenServiceImpl, BasicWebClientProviderImpl
├── opentmf-basic-webclients-starter/       # BasicAuthClients, BasicBeanRegistrationUtil, BasicWebClientsStarterAutoConfiguration
├── opentmf-openid-webclient-provider/      # OpenidClientProperties, OpenidTokenProperties, OpenidTokenServiceImpl, OpenidWebClientProviderImpl
├── opentmf-openid-webclients-starter/      # OpenidClients, OpenIdBeanRegistrationUtil, OpenidWebClientsStarterAutoConfiguration
└── test-all-clients/
```

## Target Module Structure

```
opentmf-http-clients/                       # Renamed root (new repo)
├── opentmf-http-clients-common/            # Renamed from opentmf-webclients-common
│   ├── model/
│   │   ├── ClientProperties                # Concrete, replaces abstract BaseClientProperties
│   │   ├── BasicAuthConfig                 # Nested auth config
│   │   ├── BearerAuthConfig                # Nested auth config
│   │   ├── AuthType                        # Enum: NONE, BASIC, BEARER
│   │   ├── ClientType                      # Enum: REACTIVE, REST
│   │   └── HttpClientType                  # Enum: APACHE, JDK
│   ├── service/
│   │   ├── TokenService                    # Reactive: Mono<String>
│   │   ├── SyncTokenService                # Synchronous: String (new)
│   │   └── NoOpTokenService                # Implements both (returns empty)
│   └── util/
│       ├── HttpClientUtil                  # Shared: retryable status codes, exception creation (new)
│       ├── WebClientConfigUtil             # Builds reactive HttpClient + WebClient
│       ├── WebClientUtil                   # Reactive retry, error handling (delegates to HttpClientUtil)
│       ├── RestTemplateUtil                # Sync retry, error handling (delegates to HttpClientUtil) (new)
│       └── RestTemplateFactory             # Interface for building RestTemplate (new)
│
├── opentmf-http-clients-bearer-provider/   # Renamed from opentmf-openid-webclient-provider
│   ├── TokenEntry                          # Caffeine cache entry wrapper (no reactive/REST dep)
│   ├── reactive/                           # Classes referencing WebClient/Mono
│   │   ├── OpenidTokenServiceImpl          # Reactive bearer token retrieval (Caffeine cache)
│   │   └── OpenidTokenClientImpl           # Reactive HTTP call for token
│   └── sync/                               # Classes referencing RestTemplate
│       ├── SyncBearerTokenServiceImpl      # Sync bearer token retrieval (new)
│       └── SyncTokenClientImpl             # Sync HTTP call for token (new)
│
├── opentmf-http-clients-starter/           # Single starter replacing both basic & openid starters
│   ├── OpentmfHttpClientsConfig            # @ConfigurationProperties(prefix = "opentmf")
│   ├── OpentmfHttpClientsAutoConfiguration # Imports conditional configs below
│   ├── CommonBeanRegistrar                 # Always active: registers {id}ClientProperties
│   ├── reactive/                           # @ConditionalOnClass(WebClient.class)
│   │   └── ReactiveClientRegistrar         # Registers {id}WebClient + {id}TokenService
│   ├── rest/                               # @ConditionalOnClass(RestTemplate.class)
│   │   ├── RestClientRegistrar             # Registers {id}RestTemplate + {id}TokenService
│   │   ├── ApacheRestTemplateFactory       # @ConditionalOnClass(CloseableHttpClient.class)
│   │   └── JdkRestTemplateFactory          # Always available (JDK 11+)
│
└── test-all-clients/
```

## Current Source Files → Target Mapping

### opentmf-webclients-common → opentmf-http-clients-common

| Current File | Action | Notes |
|---|---|---|
| `BaseClientProperties` | **Replace** with concrete `ClientProperties` | No longer abstract. Adds `basicAuth`, `bearerAuth`, `paths` fields. Mutual exclusivity validation. `getAuthType()` derived method. `connectionProviderName` field **removed entirely** — the map key (clientId) is passed as a method parameter wherever the client name is needed. |
| `WebClientConfigUtil` | **Keep** | Unchanged. Builds HttpClient and WebClient from properties. |
| `WebClientUtil` | **Refactor** | Reactive retry and error handling. Delegates shared logic (retryable status codes, exception creation) to new `HttpClientUtil`. |
| *(new)* `HttpClientUtil` | **Create** | Shared logic used by both `WebClientUtil` and `RestTemplateUtil`: retryable HTTP status codes, `isRetryableStatus`, `createException`. |
| *(new)* `RestTemplateUtil` | **Create** | Synchronous counterpart to `WebClientUtil`. Provides `executeWithRetry` (exponential backoff with jitter), `handleError` (converts `RestClientResponseException` to typed exception), `shouldRetryOn`. |
| `TokenUtil` | **Rename** constants | `WEB_CLIENT` → `WEB_CLIENT`, `TOKEN_SERVICE` → `TOKEN_SERVICE`, `CLIENT_PROPERTIES` → `CLIENT_PROPERTIES` (values stay the same). |
| `TokenService` | **Keep** | Unchanged. |
| `WebClientProvider` | **Refactor** | Parameterize on `ClientProperties` instead of `P extends BaseClientProperties`. |
| `WebClientProviderBaseImpl` | **Refactor** | Same. Parameterize on `ClientProperties`. |
| `OpenTmfWebClientException` | **Rename** to `OpenTmfClientResponseException` | Shared exception for both reactive and REST error handling. Redundant `Serializable` removed (`Throwable` already implements it). Carries `responseBody` field for raw body access. |
| *(new)* `OpenTmfClientNotFoundException` | **Create** | Extends `OpenTmfClientResponseException`. Thrown automatically for 404 responses by both RestTemplate and WebClient auto-wrapping. |
| *(new)* `ErrorBodyExtractor` | **Create** | Intelligent extraction of human-readable error messages from JSON bodies (RFC 7807, TMF, OAuth2, Spring Boot, generic). Safe fallbacks for binary/empty bodies with truncation. |
| *(new)* `OpenTmfResponseErrorHandler` | **Create** | `ResponseErrorHandler` for RestTemplate. Auto-wraps HTTP errors into `OpenTmfClientResponseException`/`OpenTmfClientNotFoundException`. Registered on all library-created `RestTemplate` instances. |
| *(new)* `BasicAuthConfig` | **Create** | `username`, `password`, `charset` fields with validation. |
| *(new)* `BearerAuthConfig` | **Create** | `useMock`, `tokenUrl`, `clientId`, `clientSecret`, `fallbackExpiresInSeconds` (default 3600), `cacheSafetyFactor` (default 0.9), `tokenField`, `usernameField`, `formData` fields with validation. `cacheExpirySeconds` removed — TTL is computed dynamically from each token's `expires_in`, falling back to `defaultExpiresInSeconds` when absent. |
| *(new)* `AuthType` | **Create** | Enum: `NONE`, `BASIC`, `BEARER`. |
| *(new)* `ClientType` | **Create** | Enum: `REACTIVE`, `REST`. |
| *(new)* `HttpClientType` | **Create** | Enum: `APACHE`, `JDK`. |
| *(new)* `SyncTokenService` | **Create** | Interface returning `String` directly. Synchronous counterpart to `TokenService`. |
| *(new)* `NoOpTokenService` | **Create** | Implements both `TokenService` and `SyncTokenService`. Returns empty token for no-auth clients. |
| *(new)* `RestTemplateFactory` | **Create** | Interface: `RestTemplate create(String clientId, ClientProperties props)`. Contract for pluggable HTTP client implementations. |

### opentmf-basic-webclient-provider → eliminated as separate module

| Current File | Action | Notes |
|---|---|---|
| `BasicClientProperties` | **Delete** | Replaced by `ClientProperties` + `BasicAuthConfig`. |
| `BasicTokenProperties` | **Delete** | Replaced by `BasicAuthConfig`. |
| `BasicAuthClients` | **Delete** | Replaced by `OpentmfClientsConfig`. |
| `BasicTokenService` | **Move** to `opentmf-http-clients-common` | Interface stays, just relocate. |
| `BasicTokenServiceImpl` | **Move** to `opentmf-http-clients-common` | Implementation uses `BasicAuthConfig` instead of `BasicTokenProperties`. |
| `BasicWebClientProvider` | **Delete** | Merged into unified provider. |
| `BasicWebClientProviderImpl` | **Delete** | Logic merged into unified provider. |
| `BasicWebClientProviderAutoConfiguration` | **Delete** | Replaced by unified auto-config. |
| `BasicWebClientException` | **Keep/Move** to `opentmf-http-clients-common` | |

### opentmf-openid-webclient-provider → opentmf-http-clients-bearer-provider

| Current File | Action | Notes |
|---|---|---|
| `OpenidClientProperties` | **Delete** | `paths` moved to `ClientProperties`. Token config replaced by `BearerAuthConfig`. |
| `OpenidTokenProperties` | **Delete** | Replaced by `BearerAuthConfig`. |
| `OpenidClients` | **Delete** | Replaced by `OpentmfClientsConfig`. |
| `OpenidTokenService` | **Keep** | Interface stays, relocate if desired. |
| `OpenidTokenServiceImpl` | **Refactor** | Uses `BearerAuthConfig` instead of `OpenidTokenProperties`. |
| `OpenidTokenServiceMockImpl` | **Keep** | |
| `OpenidTokenClient` | **Keep** | |
| `OpenidTokenClientImpl` | **Refactor** | Uses `ClientProperties` + `BearerAuthConfig`. |
| `OpenidWebClientProvider` | **Delete** | Merged into unified provider. |
| `OpenidWebClientProviderImpl` | **Delete** | Logic merged into unified provider. |
| `OpenidWebClientProviderAutoConfiguration` | **Delete** | Replaced by unified auto-config. |
| `OpenidTokenUtil` | **Keep** | |
| `OpenidWebClientException` | **Keep** | |
| *(new)* `SyncBearerTokenServiceImpl` | **Create** | Synchronous bearer token retrieval using `SyncTokenClientImpl`. Caches tokens in the same Caffeine cache with dynamic TTL. Implements `SyncTokenService`. |
| *(new)* `SyncTokenClientImpl` | **Create** | Synchronous HTTP call for bearer token using `RestTemplate`. Counterpart to `OpenidTokenClientImpl`. |

### opentmf-basic-webclients-starter + opentmf-openid-webclients-starter → opentmf-http-clients-starter

| Current File | Action | Notes |
|---|---|---|
| `BasicAuthClients` | **Delete** | |
| `OpenidClients` | **Delete** | |
| `BasicBeanRegistrationUtil` | **Delete** | |
| `OpenIdBeanRegistrationUtil` | **Delete** | |
| `BasicWebClientsStarterAutoConfiguration` | **Delete** | |
| `OpenidWebClientsStarterAutoConfiguration` | **Delete** | |
| *(new)* `OpentmfHttpClientsConfig` | **Create** | `@ConfigurationProperties(prefix = "opentmf")` with global `clientType`, `httpClientType` defaults + `Map<String, ClientProperties> clients`. |
| *(new)* `OpentmfHttpClientsAutoConfiguration` | **Create** | Top-level auto-config. Imports conditional configs. |
| *(new)* `CommonBeanRegistrar` | **Create** | Always active. Iterates clients map, registers `{id}ClientProperties` for each. Delegates to reactive/REST registrars based on resolved `clientType`. |
| *(new)* `ReactiveClientRegistrar` | **Create** | `@ConditionalOnClass(WebClient.class)`. Registers `{id}WebClient` + `{id}TokenService`. References reactive bearer-provider classes. |
| *(new)* `RestClientRegistrar` | **Create** | `@ConditionalOnClass(RestTemplate.class)`. Registers `{id}RestTemplate` + `{id}TokenService` (of type `SyncTokenService`). Looks up available `RestTemplateFactory` by `HttpClientType`. |
| *(new)* `ApacheRestTemplateFactory` | **Create** | `@ConditionalOnClass(CloseableHttpClient.class)`. Builds `RestTemplate` with Apache HttpClient 5. Applies timeouts, proxy, mTLS. |
| *(new)* `JdkRestTemplateFactory` | **Create** | Always available (JDK 11+). Builds `RestTemplate` with JDK `HttpClient`. Applies timeouts, proxy, mTLS. |

## Key Java Classes (Target State)

### ClientProperties

```java
@Getter @Setter @Validated
public class ClientProperties {

  // connectionProviderName removed — the map key (clientId) is passed
  // as a parameter to WebClientConfigUtil, cache creation, etc.

  @Positive
  private int maxConnections = 500;  // ConnectionProvider.DEFAULT_POOL_MAX_CONNECTIONS

  @Positive
  private int requestTimeoutMillis = 30000;

  @Positive
  private long responseTimeoutMillis = 45000;

  @PositiveOrZero
  private int numRetries = 3;

  @Positive
  private long retryWaitMillis = 5000;

  private Map<String, String> fixedHeaders;
  private ProxyConfig proxyConfig;
  private Certificates certificates;

  @Valid
  private BasicAuthConfig basicAuth;

  @Valid
  private BearerAuthConfig bearerAuth;

  private Map<String, PathScope> paths;

  private ClientType clientType;          // Per-client override; null = use global default
  private HttpClientType httpClientType;  // Per-client override; null = use global default

  @AssertTrue(message = "Cannot specify both basic-auth and bearer-auth")
  private boolean isAuthMutuallyExclusive() {
    return basicAuth == null || bearerAuth == null;
  }

  public AuthType getAuthType() {
    if (basicAuth != null) return AuthType.BASIC;
    if (bearerAuth != null) return AuthType.BEARER;
    return AuthType.NONE;
  }

  // ProxyConfig, Certificates, PathScope as inner classes (same as current)
}
```

### BasicAuthConfig

```java
@Getter @Setter @Validated
public class BasicAuthConfig {
  @NotBlank private String username;
  @NotBlank private String password;
  private String charset = "US-ASCII";
}
```

### BearerAuthConfig

```java
@Getter @Setter @Validated
public class BearerAuthConfig {
  private boolean useMock = false;
  @NotNull private URI tokenUrl;
  private String clientId;
  private String clientSecret;
  @NotEmpty private String tokenField = "access_token";
  @NotEmpty private String expiresInField = "expires_in";
  @NotEmpty private String usernameField = "username";
  @NotEmpty private Map<String, String> formData;

  /**
   * Assumed token lifetime when the response does not include expires_in.
   */
  @Positive
  private long fallbackExpiresInSeconds = 3600;

  /**
   * Fraction of the token's expires_in to use as cache TTL.
   * Default 0.9 means a token valid for 3600s is cached for 3240s.
   * Set lower for tighter safety margins.
   */
  @DecimalMin("0.1") @DecimalMax("0.99")
  private double cacheSafetyFactor = 0.9;
}
```

### OpentmfHttpClientsConfig

```java
@ConfigurationProperties(prefix = "opentmf")
@Getter @Setter @Validated
public class OpentmfHttpClientsConfig {

  private ClientType clientType = ClientType.REST;           // Global default
  private HttpClientType httpClientType = HttpClientType.JDK;    // Global default for REST clients

  @NotEmpty
  private Map<@NotEmpty String, @Valid ClientProperties> clients;

  /**
   * Resolves the effective client type for a given client.
   * Per-client override takes precedence over global default.
   */
  public ClientType resolveClientType(ClientProperties props) {
    return props.getClientType() != null ? props.getClientType() : clientType;
  }

  /**
   * Resolves the effective HTTP client type for a given REST client.
   * Per-client override takes precedence over global default.
   */
  public HttpClientType resolveHttpClientType(ClientProperties props) {
    return props.getHttpClientType() != null ? props.getHttpClientType() : httpClientType;
  }
}
```

### ClientType

```java
public enum ClientType {
  REACTIVE,  // WebClient (Reactor Netty)
  REST       // RestTemplate (pluggable HTTP client)
}
```

### HttpClientType

```java
public enum HttpClientType {
  APACHE,  // Apache HttpClient 5 (HttpComponents)
  JDK      // JDK HttpClient (Java 11+)
}
```

### SyncTokenService

```java
public interface SyncTokenService {
  String getToken();
  String getToken(String additionalScopes);
}
```

### RestTemplateFactory

```java
public interface RestTemplateFactory {
  RestTemplate create(String clientId, ClientProperties properties);
}
```

### Conditional Auto-Configuration Architecture

The starter uses `@ConditionalOnClass` to ensure classes referencing optional dependencies are
never loaded when those dependencies are absent. This is the same pattern Spring Boot uses
internally (e.g. `WebFluxAutoConfiguration` is only active when WebFlux is on the classpath).

**Key rule**: No class may import/reference types from an optional dependency unless that class
itself is guarded by `@ConditionalOnClass` (or is only instantiated by a guarded class).

```
OpentmfHttpClientsAutoConfiguration          ← always loaded
├── CommonBeanRegistrar                      ← always loaded (registers ClientProperties)
├── ReactiveClientRegistrar                  ← @ConditionalOnClass(WebClient.class)
│   └── references: WebClient, Mono, WebClientConfigUtil, OpenidTokenServiceImpl, ...
└── RestClientRegistrar                      ← @ConditionalOnClass(RestTemplate.class)
    ├── references: RestTemplate, SyncTokenService, SyncBearerTokenServiceImpl, ...
    └── delegates to:
        ├── ApacheRestTemplateFactory        ← @ConditionalOnClass(CloseableHttpClient.class)
        └── JdkRestTemplateFactory           ← always available
```

**Startup validation**: `CommonBeanRegistrar` iterates the clients map and checks each client's
resolved `clientType`. If `REACTIVE` is requested but `ReactiveClientRegistrar` is not present
(WebFlux not on classpath), it throws a descriptive `IllegalStateException`. Same for REST.

### CommonBeanRegistrar

```java
@RequiredArgsConstructor
@Slf4j
public class CommonBeanRegistrar {

  private final ConfigurableListableBeanFactory factory;
  private final OpentmfHttpClientsConfig config;
  private final Optional<ReactiveClientRegistrar> reactiveRegistrar;
  private final Optional<RestClientRegistrar> restRegistrar;

  void registerAllBeans() {
    config.getClients().forEach((clientId, properties) -> {
      registerIfAbsent(clientId + CLIENT_PROPERTIES, properties);

      ClientType effectiveType = config.resolveClientType(properties);
      switch (effectiveType) {
        case REACTIVE -> reactiveRegistrar
            .orElseThrow(() -> new IllegalStateException(
                "Client '" + clientId + "' requires client-type: reactive, "
                + "but spring-webflux is not on the classpath. "
                + "Add spring-boot-starter-webflux or change to client-type: rest."))
            .registerBeans(clientId, properties);
        case REST -> restRegistrar
            .orElseThrow(() -> new IllegalStateException(
                "Client '" + clientId + "' requires client-type: rest, "
                + "but RestTemplate is not on the classpath."))
            .registerBeans(clientId, properties);
      }
    });
  }
}
```

### ReactiveClientRegistrar

```java
@Configuration
@ConditionalOnClass(WebClient.class)
@RequiredArgsConstructor
public class ReactiveClientRegistrar {

  private final ConfigurableListableBeanFactory factory;
  private final WebClient.Builder webClientBuilder;
  private final Logbook logbook;

  void registerBeans(String clientId, ClientProperties properties) {
    registerIfAbsent(clientId + WEB_CLIENT, buildWebClient(clientId, properties));
    registerIfAbsent(clientId + TOKEN_SERVICE, buildTokenService(clientId, properties));
  }

  private WebClient buildWebClient(String clientId, ClientProperties properties) {
    var httpClient = WebClientConfigUtil.httpClient(logbook, clientId, properties);
    return WebClientConfigUtil.createWebClient(webClientBuilder, httpClient, properties);
  }

  private TokenService buildTokenService(String clientId, ClientProperties properties) {
    return switch (properties.getAuthType()) {
      case NONE    -> new NoOpTokenService();
      case BASIC   -> new BasicTokenServiceImpl(properties.getBasicAuth());
      case BEARER  -> buildReactiveBearerTokenService(clientId, properties);
    };
  }

  // ...
}
```

### RestClientRegistrar

```java
@Configuration
@ConditionalOnClass(RestTemplate.class)
@RequiredArgsConstructor
public class RestClientRegistrar {

  private final ConfigurableListableBeanFactory factory;
  private final OpentmfHttpClientsConfig config;
  private final Map<HttpClientType, RestTemplateFactory> restTemplateFactories;

  void registerBeans(String clientId, ClientProperties properties) {
    HttpClientType httpType = config.resolveHttpClientType(properties);
    RestTemplateFactory rtFactory = restTemplateFactories.get(httpType);
    if (rtFactory == null) {
      throw new IllegalStateException(
          "Client '" + clientId + "' requires http-client-type: " + httpType
          + ", but no matching library is on the classpath. "
          + availableFactoriesHint());
    }
    registerIfAbsent(clientId + REST_TEMPLATE, rtFactory.create(clientId, properties));
    registerIfAbsent(clientId + TOKEN_SERVICE, buildSyncTokenService(clientId, properties));
  }

  private SyncTokenService buildSyncTokenService(String clientId, ClientProperties properties) {
    return switch (properties.getAuthType()) {
      case NONE    -> new NoOpTokenService();
      case BASIC   -> new SyncBasicTokenServiceImpl(properties.getBasicAuth());
      case BEARER  -> buildSyncBearerTokenService(clientId, properties);
    };
  }

  // ...
}
```

### Bean Name Constants

```java
public static final String WEB_CLIENT = "WebClient";
public static final String REST_TEMPLATE = "RestTemplate";
public static final String TOKEN_SERVICE = "TokenService";
// SYNC_TOKEN_SERVICE removed — REST clients also use TOKEN_SERVICE suffix
public static final String CLIENT_PROPERTIES = "ClientProperties";
```

### Starter pom.xml Dependency Strategy

```xml
<!-- Always required -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-autoconfigure</artifactId>
</dependency>

<!-- Reactive: optional — only needed if client-type: reactive is used -->
<dependency>
  <groupId>org.springframework</groupId>
  <artifactId>spring-webflux</artifactId>
  <optional>true</optional>
</dependency>
<dependency>
  <groupId>io.projectreactor.netty</groupId>
  <artifactId>reactor-netty-http</artifactId>
  <optional>true</optional>
</dependency>

<!-- REST HTTP client implementations: optional — pick one if using client-type: rest -->
<dependency>
  <groupId>org.apache.httpcomponents.client5</groupId>
  <artifactId>httpclient5</artifactId>
  <optional>true</optional>
</dependency>
<!-- JDK HttpClient: no dependency needed (Java 11+) -->
```

## Implementation Steps

### Step 1: Rename root project

- New repository: `opentmf-http-clients`. Archive the old `opentmf-web-clients` repo with a pointer to the new one.
- Root `pom.xml`: set `artifactId` to `opentmf-http-clients`, update `<name>`, `<description>`, `<url>`, `<scm>`.
- Add new dependencies to root `pom.xml` dependency management:
  - `com.github.ben-manes.caffeine:caffeine` (managed by Spring Boot BOM)
  - `org.apache.httpcomponents.client5:httpclient5` (for Apache RestTemplate factory)
  

### Step 2: Create opentmf-http-clients-common

- Rename `opentmf-webclients-common` directory and its `pom.xml` `artifactId`.
- Create `AuthType` enum (`NONE`, `BASIC`, `BEARER`).
- Create `ClientType` enum (`REACTIVE`, `REST`).
- Create `HttpClientType` enum (`APACHE`, `JDK`).
- Create `BasicAuthConfig` class.
- Create `BearerAuthConfig` class.
- Replace abstract `BaseClientProperties` with concrete `ClientProperties`. Add `basicAuth`, `bearerAuth`, `paths`, `clientType`, `httpClientType` fields. Mutual exclusivity validation. `getAuthType()` derived method. `connectionProviderName` field **removed entirely**.
- Create `SyncTokenService` interface (returns `String` directly).
- Move `BasicTokenService` interface and `BasicTokenServiceImpl` here (refactored to use `BasicAuthConfig`).
- Create `SyncBasicTokenServiceImpl` (synchronous basic auth token — Base64 encoding).
- Create `NoOpTokenService` implementing both `TokenService` and `SyncTokenService` (returns empty token for no-auth clients).
- Create `RestTemplateFactory` interface (`RestTemplate create(String clientId, ClientProperties props)`).
- Refactor `WebClientProvider` and `WebClientProviderBaseImpl` to use `ClientProperties` directly instead of generic `P extends BaseClientProperties`.
- Update `WebClientConfigUtil` to accept `ClientProperties` instead of `BaseClientProperties`. Methods that previously read `connectionProviderName` from properties now take `clientId` as an explicit parameter.

### Step 3: Refactor opentmf-http-clients-bearer-provider

- Rename `opentmf-openid-webclient-provider` directory and `artifactId`.
- Delete `OpenidClientProperties`, `OpenidTokenProperties`, `OpenidClients`.
- Refactor `OpenidTokenClientImpl` to use `ClientProperties` + `BearerAuthConfig`.
- Refactor `OpenidTokenServiceImpl` to use `BearerAuthConfig` and the new dynamic token caching (see below).
- Create `SyncTokenClientImpl` — synchronous HTTP call for bearer token using `RestTemplate`.
- Create `SyncBearerTokenServiceImpl` — synchronous bearer token service implementing `SyncTokenService`. Uses the same Caffeine cache pattern with dynamic TTL.
- Refactor `OpenidWebClientProviderImpl` → no longer needed. Its logic moves to `BeanRegistrationUtil`.
- Delete `OpenidWebClientProviderAutoConfiguration` (replaced by unified auto-config).
- **Replace ehcache (JCache) with Caffeine** for bearer token caching (see "Dynamic Token Caching" section below).

### Step 4: Create opentmf-http-clients-starter

- Create new module replacing both `opentmf-basic-webclients-starter` and `opentmf-openid-webclients-starter`.
- Declare reactive dependencies (`spring-webflux`, `reactor-netty-http`) and REST HTTP client deps (`httpclient5`) as `<optional>true</optional>` in `pom.xml`. The consumer's application pulls in what it needs.
- Create `OpentmfHttpClientsConfig` with `@ConfigurationProperties(prefix = "opentmf")`, including global `clientType` and `httpClientType` defaults.
- Create conditional auto-configuration classes (see "Conditional Auto-Configuration Architecture" section):
  - `CommonBeanRegistrar` — always active. Iterates clients map, registers `{id}ClientProperties`, delegates to the appropriate registrar based on `clientType`. Throws descriptive error if the required registrar is absent.
  - `ReactiveClientRegistrar` — `@ConditionalOnClass(WebClient.class)`. Registers `{id}WebClient` + `{id}TokenService`. References reactive bearer-provider classes only.
  - `RestClientRegistrar` — `@ConditionalOnClass(RestTemplate.class)`. Registers `{id}RestTemplate` + `{id}TokenService` (of type `SyncTokenService`). Looks up available `RestTemplateFactory` by `HttpClientType`.
- Create `RestTemplateFactory` implementations, each conditionally loaded:
  - `ApacheRestTemplateFactory`: `@ConditionalOnClass(CloseableHttpClient.class)`. Uses `HttpComponentsClientHttpRequestFactory`.
  - `JdkRestTemplateFactory`: Always available (JDK 11+). Uses `JdkClientHttpRequestFactory`.
- Each factory applies timeouts, proxy, and mTLS from `ClientProperties`.
- Create `OpentmfHttpClientsAutoConfiguration` that imports the conditional configs.
- Register in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
- Delete old starter modules.

### Step 5: Delete obsolete modules

- Delete `opentmf-basic-webclient-provider/` directory.
- Delete `opentmf-basic-webclients-starter/` directory.
- Delete `opentmf-openid-webclients-starter/` directory.
- Remove from root `pom.xml` `<modules>`.

### Step 6: Update test-all-clients

- Update test configurations to use the new YAML structure (including `client-type` and `http-client-type`).
- Add test cases for REST clients alongside existing reactive tests.
- Update test code to use `ClientProperties` instead of `BasicClientProperties`/`OpenidClientProperties`.
- Update auto-configuration references.
- Test all combinations: reactive+basic, reactive+bearer, reactive+none, rest+basic, rest+bearer, rest+none.
- Test different HTTP client implementations for REST: apache, jdk.
- Add `MtlsIT` integration test verifying HTTPS/mTLS certificate configuration for all three client types (WebClient, RestTemplate/JDK, RestTemplate/Apache) using programmatically generated certificates via Bouncy Castle and MockServer Netty.
- Add `MtlsCertificateUtil` test utility for runtime CA and client certificate generation (no committed binary cert files).
- Remove `com.squareup.okhttp3:mockwebserver` and `com.squareup.okhttp3:okhttp-tls` test dependencies (consolidated to `mockserver-netty`).

### Step 7: Update documentation

- Update `README.md` with new configuration format, including REST client examples.
- Document the `client-type` and `http-client-type` properties.
- Document which Maven dependencies are needed for each HTTP client type.
- Update `README_MTLS.md` if affected.

## Dynamic Token Caching

### Problem

The current design uses a static `cache-expiry-seconds` property with ehcache (JCache). This is fragile: if a Keycloak admin reduces the token expiry period, cached tokens may outlive their validity, causing silent auth failures.

### Solution

Replace the static TTL with a dynamic per-token TTL derived from the `expires_in` field in the OAuth2 token response. Replace ehcache with Caffeine, which supports per-entry expiry.

### How it works

1. Token response is received:
   ```json
   { "access_token": "eyJ...", "expires_in": 3600, "token_type": "Bearer" }
   ```
2. `expires_in` is read from the response (field name configurable via `expiresInField`).
3. Cache TTL is computed: `expires_in * cacheSafetyFactor` (default factor: 0.9).
4. The token is stored in a Caffeine cache with that individual TTL.

**Examples with default 0.9 factor:**

| Token `expires_in` | Cache TTL | Safety margin |
|---|---|---|
| 3600s (1 hour) | 3240s (54 min) | 6 min |
| 600s (10 min) | 540s (9 min) | 1 min |
| 180s (3 min) | 162s (2:42) | 18s |
| 60s (1 min) | 54s | 6s |

### Configuration

`cache-expiry-seconds` is **removed**. An optional `cache-safety-factor` replaces it:

```yaml
bearer-auth:
  token-url: https://auth.example.com/token
  # cache-safety-factor: 0.9     # optional, default 0.9
  form-data:
    grant_type: client_credentials
```

Most users never configure this — the default is sensible. Only override if you need tighter or looser margins.

### Implementation

**Token entry wrapper:**

```java
@Value
public class TokenEntry {
  ObjectNode tokenData;
  Duration cacheDuration;

  public static TokenEntry from(ObjectNode tokenData, double safetyFactor) {
    long expiresIn = tokenData.path("expires_in").asLong();
    long cacheSecs = Math.max(1, (long) (expiresIn * safetyFactor));
    return new TokenEntry(tokenData, Duration.ofSeconds(cacheSecs));
  }
}
```

**Caffeine cache with per-entry expiry:**

```java
Cache<String, TokenEntry> tokenCache = Caffeine.newBuilder()
    .expireAfter(new Expiry<String, TokenEntry>() {
      @Override
      public long expireAfterCreate(String key, TokenEntry entry, long currentTime) {
        return entry.getCacheDuration().toNanos();
      }

      @Override
      public long expireAfterUpdate(String key, TokenEntry entry,
          long currentTime, long currentDuration) {
        return entry.getCacheDuration().toNanos();
      }

      @Override
      public long expireAfterRead(String key, TokenEntry entry,
          long currentTime, long currentDuration) {
        return currentDuration;
      }
    })
    .build();
```

**Reactive token retrieval flow (in OpenidTokenServiceImpl):**

```java
public Mono<String> getToken(String additionalScopes) {
  String cacheKey = buildCacheKey(additionalScopes);
  TokenEntry cached = tokenCache.getIfPresent(cacheKey);
  if (cached != null) {
    return Mono.just(extractAccessToken(cached.getTokenData()));
  }
  return tokenClient.getToken(additionalScopes)
      .map(tokenData -> {
        var entry = TokenEntry.from(tokenData, bearerAuthConfig.getCacheSafetyFactor());
        tokenCache.put(cacheKey, entry);
        return extractAccessToken(tokenData);
      });
}
```

**Synchronous token retrieval flow (in SyncBearerTokenServiceImpl):**

```java
public String getToken(String additionalScopes) {
  String cacheKey = buildCacheKey(additionalScopes);
  TokenEntry cached = tokenCache.getIfPresent(cacheKey);
  if (cached != null) {
    return extractAccessToken(cached.getTokenData());
  }
  ObjectNode tokenData = syncTokenClient.getToken(additionalScopes);
  var entry = TokenEntry.from(tokenData, bearerAuthConfig.getCacheSafetyFactor());
  tokenCache.put(cacheKey, entry);
  return extractAccessToken(tokenData);
}
```

Both implementations share the same `TokenEntry` and `Caffeine` cache configuration. The only difference is the token retrieval mechanism: reactive (`OpenidTokenClientImpl` using `WebClient`) vs. synchronous (`SyncTokenClientImpl` using `RestTemplate`).

**SyncTokenClientImpl (new):**

```java
@RequiredArgsConstructor
public class SyncTokenClientImpl {
  private final RestTemplate tokenRestTemplate;
  private final BearerAuthConfig config;

  public ObjectNode getToken(String additionalScopes) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    headers.setBasicAuth(config.getClientId(), config.getClientSecret());

    MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
    config.getFormData().forEach(formData::add);
    if (additionalScopes != null && !additionalScopes.isBlank()) {
      String existing = formData.getFirst("scope");
      formData.set("scope", existing != null ? existing + " " + additionalScopes : additionalScopes);
    }

    HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(formData, headers);
    return tokenRestTemplate.postForObject(config.getTokenUrl(), request, ObjectNode.class);
  }
}
```

### Dependencies

**Remove:**
- `javax.cache:cache-api` (JCache)
- `org.ehcache:ehcache`

**Add:**
- `com.github.ben-manes.caffeine:caffeine` (already managed by Spring Boot BOM — no version needed)

## Exposed Bean Convention

For each entry in the `clients` map with key `clientId`, the registered beans depend on the effective `client-type`:

### Reactive clients (`client-type: reactive`)

| Bean Name | Type | Always |
|---|---|---|
| `{clientId}ClientProperties` | `ClientProperties` | Yes |
| `{clientId}WebClient` | `WebClient` | Yes |
| `{clientId}TokenService` | `TokenService` | Yes |

The `TokenService` implementation varies by auth type:
- **No auth**: `NoOpTokenService`
- **basic-auth**: `BasicTokenServiceImpl`
- **bearer-auth**: `OpenidTokenServiceImpl` (or mock)

### REST clients (`client-type: rest`)

| Bean Name | Type | Always |
|---|---|---|
| `{clientId}ClientProperties` | `ClientProperties` | Yes |
| `{clientId}RestTemplate` | `RestTemplate` | Yes |
| `{clientId}TokenService` | `SyncTokenService` | Yes |

The `SyncTokenService` implementation varies by auth type:
- **No auth**: `NoOpTokenService`
- **basic-auth**: `SyncBasicTokenServiceImpl`
- **bearer-auth**: `SyncBearerTokenServiceImpl` (or mock)

### Key difference

A single client produces **either** reactive beans **or** REST beans — never both. This keeps the mental model simple and avoids bean name collisions. If a downstream project needs both a `WebClient` and a `RestTemplate` for the same backend, it declares two clients with different IDs and different `client-type` values.

## Downstream Impact

### Existing reactive consumers (no changes needed)

`opentmf-clients-base` and `opentmf-v4-clients` retrieve beans by `{clientId} + "WebClient"`, `{clientId} + "TokenService"`, and `{clientId} + "ClientProperties"`. These suffixes and types are unchanged for reactive clients, so **downstream projects require no code changes beyond a dependency version bump, YAML migration, and a single import rename**.

The import change: `BaseClientProperties` → `ClientProperties`. This is a single find-and-replace.

### New REST consumers

Downstream projects wanting synchronous clients will:
1. Set `client-type: rest` in YAML (per-client or global).
2. Add the appropriate HTTP client dependency (Apache or JDK — JDK needs no extra dependency).
3. Inject `{clientId}RestTemplate` and `{clientId}TokenService` (of type `SyncTokenService`) instead of `WebClient`/`TokenService`.

This is a new capability — existing consumers are not affected unless they opt in.

## YAML Migration Guide

### Migration Rules

| Old | New | Notes |
|---|---|---|
| `opentmf.webclient.basic.<id>.*` | `opentmf.clients.<id>.*` | Move out of `basic:` block |
| `opentmf.webclient.openid.<id>.*` | `opentmf.clients.<id>.*` | Move out of `openid:` block |
| `connection-provider-name: X` | *(remove)* | Derived from the map key |
| `token-config:` (under basic) | `basic-auth:` | Rename block |
| `token-config:` (under openid) | `bearer-auth:` | Rename block |
| `token-config.basicAuthUsername` | `bearer-auth.client-id` | Renamed for clarity |
| `token-config.basicAuthPassword` | `bearer-auth.client-secret` | Renamed for clarity |
| `token-config.cache-expiry-seconds` | *(remove)* | Now dynamic — derived from token's `expires_in` |
| *(no equivalent)* | `bearer-auth.cache-safety-factor` | Optional (default 0.9). Fraction of `expires_in` to cache. |
| *(no equivalent)* | *(omit auth block)* | No-auth client |
| *(no equivalent)* | `opentmf.client-type` | Global default: `reactive` or `rest`. |
| *(no equivalent)* | `opentmf.http-client-type` | Global default for REST: `jdk` or `apache`. |
| *(no equivalent)* | `client-type` (per client) | Per-client override of global `client-type`. |
| *(no equivalent)* | `http-client-type` (per client) | Per-client override of global `http-client-type`. |

### Example 1: Basic auth client

**Before:**
```yaml
opentmf:
  webclient:
    basic:
      firstBasic:
        connection-provider-name: firstBasic
        fixed-headers:
          header1: value1
        token-config:
          username: user1
          password: pass1
```

**After:**
```yaml
opentmf:
  clients:
    firstBasic:
      fixed-headers:
        header1: value1
      basic-auth:
        username: user1
        password: pass1
```

### Example 2: Bearer (OpenID) auth client

**Before:**
```yaml
opentmf:
  webclient:
    openid:
      firstOpenId:
        connection-provider-name: firstOpenId
        fixed-headers:
          header1: value1
        token-config:
          use-mock: false
          token-url: http://localhost:1080/token
          basicAuthUsername: user
          basicAuthPassword: pass
          cache-expiry-seconds: 1
          username-field: username
          form-data:
            username: user
            password: pass
            scope: openid
            grant_type: password
```

**After:**
```yaml
opentmf:
  clients:
    firstOpenId:
      fixed-headers:
        header1: value1
      bearer-auth:
        use-mock: false
        token-url: http://localhost:1080/token
        client-id: user
        client-secret: pass
        username-field: username
        form-data:
          username: user
          password: pass
          scope: openid
          grant_type: password
```

### Example 3: Bearer auth client with mTLS

**Before:**
```yaml
opentmf:
  webclient:
    openid:
      secondOpenId:
        connection-provider-name: secondOpenId
        fixed-headers:
          header1: value1
          header2: value2
        certificates:
          key-store:
            password: mypassword
            pk-password: mypassword
            base64-jks: <BASE64_JKS_CONTENT>
          trust-store:
            password: mypassword
            base64-jks: <BASE64_JKS_CONTENT>
        token-config:
          use-mock: false
          token-url: http://localhost:1080/token
          basicAuthUsername: user
          basicAuthPassword: pass
          cache-expiry-seconds: 1
          username-field: username
          form-data:
            username: user
            password: pass
            scope: openid
            grant_type: password
```

**After:**
```yaml
opentmf:
  clients:
    secondOpenId:
      fixed-headers:
        header1: value1
        header2: value2
      certificates:
        key-store:
          password: mypassword
          pk-password: mypassword
          base64-jks: <BASE64_JKS_CONTENT>
        trust-store:
          password: mypassword
          base64-jks: <BASE64_JKS_CONTENT>
      bearer-auth:
        use-mock: false
        token-url: http://localhost:1080/token
        client-id: user
        client-secret: pass
        username-field: username
        form-data:
          username: user
          password: pass
          scope: openid
          grant_type: password
```

### Example 4: Bearer auth client with paths

**Before:**
```yaml
opentmf:
  webclient:
    openid:
      myClient:
        connection-provider-name: myClient
        max-connections: 500
        request-timeout-millis: 30000
        response-timeout-millis: 50000
        num-retries: 3
        retry-wait-millis: 100
        fixed-headers:
          x-country-code: TR
          x-route-info: CatSync
          x-source-system: Dsync
        token-config:
          use-mock: false
          token-url: http://localhost:1080/auth/token
          form-data:
            grant-type: client_credentials
            scope: myScope
            username: myusername
        paths:
          path1:
            path: /path
            scope: scope
```

**After:**
```yaml
opentmf:
  clients:
    myClient:
      max-connections: 500
      request-timeout-millis: 30000
      response-timeout-millis: 50000
      num-retries: 3
      retry-wait-millis: 100
      fixed-headers:
        x-country-code: TR
        x-route-info: CatSync
        x-source-system: Dsync
      bearer-auth:
        use-mock: false
        token-url: http://localhost:1080/auth/token
        form-data:
          grant-type: client_credentials
          scope: myScope
          username: myusername
      paths:
        path1:
          path: /path
          scope: scope
```

### Example 5: No-auth client (new capability)

**Before:** Not possible — every client required either basic or openid auth.

**After:**
```yaml
opentmf:
  clients:
    healthCheck:
      request-timeout-millis: 5000
      response-timeout-millis: 10000
```

### Example 6: REST client with bearer auth (new capability)

**Before:** Not possible — only reactive `WebClient` was supported.

**After:**
```yaml
opentmf:
  http-client-type: apache                    # global default
  clients:
    syncClient:
      client-type: rest
      http-client-type: apache                # override: use Apache for this client
      bearer-auth:
        token-url: https://auth.example.com/token
        client-id: federation
        client-secret: mySecret
        form-data:
          grant_type: client_credentials
          scope: openid
```

Produces beans: `syncClientRestTemplate`, `syncClientTokenService`, `syncClientClientProperties`.

### Example 7: Mixed reactive and REST clients (new capability)

**Before:** Not possible.

**After:**
```yaml
opentmf:
  client-type: reactive                       # global default
  http-client-type: apache                    # global default for REST clients
  clients:
    reactiveApi:
      bearer-auth:
        token-url: https://auth.example.com/token
        client-id: federation
        client-secret: mySecret
        form-data:
          grant_type: client_credentials

    syncApi:
      client-type: rest                       # override global default
      bearer-auth:
        token-url: https://auth.example.com/token
        client-id: federation
        client-secret: mySecret
        form-data:
          grant_type: client_credentials

    healthCheck:
      client-type: rest
      # No auth block
```

Produces:
- `reactiveApiWebClient`, `reactiveApiTokenService` (reactive)
- `syncApiRestTemplate`, `syncApiTokenService` (REST + Apache)
- `healthCheckRestTemplate`, `healthCheckTokenService` (REST + Apache, no auth)

### Example 8: Full mixed configuration

**Before:**
```yaml
opentmf:
  webclient:
    basic:
      firstBasic:
        connection-provider-name: firstBasic
        fixed-headers:
          header1: value1
        token-config:
          username: user1
          password: pass1
      secondBasic:
        connection-provider-name: secondBasic
        fixed-headers:
          header1: value1
          header2: value2
        token-config:
          username: user2
          password: pass2
    openid:
      firstOpenId:
        connection-provider-name: firstOpenId
        fixed-headers:
          header1: value1
        token-config:
          use-mock: false
          token-url: http://localhost:1080/token
          basicAuthUsername: user
          basicAuthPassword: pass
          cache-expiry-seconds: 1
          username-field: username
          form-data:
            username: user
            password: pass
            scope: openid
            grant_type: password
```

**After:**
```yaml
opentmf:
  clients:
    firstBasic:
      fixed-headers:
        header1: value1
      basic-auth:
        username: user1
        password: pass1

    secondBasic:
      fixed-headers:
        header1: value1
        header2: value2
      basic-auth:
        username: user2
        password: pass2

    firstOpenId:
      fixed-headers:
        header1: value1
      bearer-auth:
        use-mock: false
        token-url: http://localhost:1080/token
        client-id: user
        client-secret: pass
        username-field: username
        form-data:
          username: user
          password: pass
          scope: openid
          grant_type: password

    healthCheck:
      # No auth block — no Authorization header
```

### Maven Dependency Changes

**Before** (users picked one or both):
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

**After** (single starter + optional HTTP client dependency for REST):
```xml
<!-- Required: the starter -->
<dependency>
  <groupId>org.opentmf.client</groupId>
  <artifactId>opentmf-http-clients-starter</artifactId>
</dependency>

<!-- Pick ONE if using client-type: rest (not needed for reactive-only) -->
<!-- Option A: Apache HttpClient 5 (recommended) -->
<dependency>
  <groupId>org.apache.httpcomponents.client5</groupId>
  <artifactId>httpclient5</artifactId>
</dependency>

<!-- Option B: JDK HttpClient — no extra dependency needed (Java 11+) -->
```

### Java Import Changes

```
// Before
import org.opentmf.client.common.model.BaseClientProperties;

// After
import org.opentmf.client.common.model.ClientProperties;
```

### Environment Variable Migration Guide (DevOps)

Spring Boot maps YAML properties to environment variables using uppercase with underscores. The restructuring changes the env var prefix and removes `TOKEN_CONFIG` in favor of `BASIC_AUTH` or `BEARER_AUTH`.

#### Naming Pattern Changes

| Old Pattern | New Pattern |
|---|---|
| `OPENTMF_WEBCLIENT_BASIC_<ID>_*` | `OPENTMF_CLIENTS_<ID>_*` |
| `OPENTMF_WEBCLIENT_OPENID_<ID>_*` | `OPENTMF_CLIENTS_<ID>_*` |
| `*_CONNECTION_PROVIDER_NAME` | *(remove — derived from `<ID>`)* |
| `*_TOKEN_CONFIG_USERNAME` | `*_BASIC_AUTH_USERNAME` |
| `*_TOKEN_CONFIG_PASSWORD` | `*_BASIC_AUTH_PASSWORD` |
| `*_TOKEN_CONFIG_TOKEN_URL` | `*_BEARER_AUTH_TOKEN_URL` |
| `*_TOKEN_CONFIG_BASIC_AUTH_USERNAME` | `*_BEARER_AUTH_CLIENT_ID` |
| `*_TOKEN_CONFIG_BASIC_AUTH_PASSWORD` | `*_BEARER_AUTH_CLIENT_SECRET` |
| `*_TOKEN_CONFIG_CACHE_EXPIRY_SECONDS` | *(remove — now dynamic)* |
| `*_TOKEN_CONFIG_FORM_DATA_*` | `*_BEARER_AUTH_FORM_DATA_*` |
| `*_TOKEN_CONFIG_USE_MOCK` | `*_BEARER_AUTH_USE_MOCK` |
| `*_TOKEN_CONFIG_TOKEN_FIELD` | `*_BEARER_AUTH_TOKEN_FIELD` |
| `*_TOKEN_CONFIG_USERNAME_FIELD` | `*_BEARER_AUTH_USERNAME_FIELD` |
| *(no equivalent)* | `OPENTMF_CLIENT_TYPE` | Global default: `REACTIVE` or `REST`. |
| *(no equivalent)* | `OPENTMF_HTTP_CLIENT_TYPE` | Global default for REST: `JDK` or `APACHE`. |
| *(no equivalent)* | `OPENTMF_CLIENTS_<ID>_CLIENT_TYPE` | Per-client override. |
| *(no equivalent)* | `OPENTMF_CLIENTS_<ID>_HTTP_CLIENT_TYPE` | Per-client override. |

#### Example: Bearer auth client "dnext"

**Before:**
```bash
OPENTMF_WEBCLIENT_OPENID_DNEXT_CONNECTION_PROVIDER_NAME=dnext
OPENTMF_WEBCLIENT_OPENID_DNEXT_TOKEN_CONFIG_TOKEN_URL=http://keycloak:8080/realms/dsync/protocol/openid-connect/token
OPENTMF_WEBCLIENT_OPENID_DNEXT_TOKEN_CONFIG_BASIC_AUTH_USERNAME=federation
OPENTMF_WEBCLIENT_OPENID_DNEXT_TOKEN_CONFIG_BASIC_AUTH_PASSWORD=Yag5k0sOczgdIHddXGcVWjz9ejnIdtBG
OPENTMF_WEBCLIENT_OPENID_DNEXT_TOKEN_CONFIG_CACHE_EXPIRY_SECONDS=570     # ← will be removed
OPENTMF_WEBCLIENT_OPENID_DNEXT_TOKEN_CONFIG_FORM_DATA_SCOPE=openid
OPENTMF_WEBCLIENT_OPENID_DNEXT_TOKEN_CONFIG_FORM_DATA_GRANT_TYPE=password
OPENTMF_WEBCLIENT_OPENID_DNEXT_TOKEN_CONFIG_FORM_DATA_USERNAME=fulfill-mvnx-e-sim
OPENTMF_WEBCLIENT_OPENID_DNEXT_TOKEN_CONFIG_FORM_DATA_PASSWORD=Gcu@S2S2025!
```

**After:**
```bash
OPENTMF_CLIENTS_DNEXT_BEARER_AUTH_TOKEN_URL=http://keycloak:8080/realms/dsync/protocol/openid-connect/token
OPENTMF_CLIENTS_DNEXT_BEARER_AUTH_CLIENT_ID=federation
OPENTMF_CLIENTS_DNEXT_BEARER_AUTH_CLIENT_SECRET=Yag5k0sOczgdIHddXGcVWjz9ejnIdtBG
# CACHE_EXPIRY_SECONDS removed — TTL derived automatically from token's expires_in
OPENTMF_CLIENTS_DNEXT_BEARER_AUTH_FORM_DATA_SCOPE=openid
OPENTMF_CLIENTS_DNEXT_BEARER_AUTH_FORM_DATA_GRANT_TYPE=password
OPENTMF_CLIENTS_DNEXT_BEARER_AUTH_FORM_DATA_USERNAME=fulfill-mvnx-e-sim
OPENTMF_CLIENTS_DNEXT_BEARER_AUTH_FORM_DATA_PASSWORD=Gcu@S2S2025!
```

**What changed:**
- `OPENTMF_WEBCLIENT_OPENID_DNEXT_` → `OPENTMF_CLIENTS_DNEXT_` (flat map, no auth-type prefix)
- `CONNECTION_PROVIDER_NAME` → removed entirely
- `TOKEN_CONFIG_` → `BEARER_AUTH_`
- `BASIC_AUTH_USERNAME` → `CLIENT_ID` (clear OAuth2 terminology)
- `BASIC_AUTH_PASSWORD` → `CLIENT_SECRET` (clear OAuth2 terminology)
- `CACHE_EXPIRY_SECONDS` → removed (TTL now derived dynamically from each token's `expires_in`)

#### Example: Basic auth client "internal"

**Before:**
```bash
OPENTMF_WEBCLIENT_BASIC_INTERNAL_CONNECTION_PROVIDER_NAME=internal
OPENTMF_WEBCLIENT_BASIC_INTERNAL_TOKEN_CONFIG_USERNAME=admin
OPENTMF_WEBCLIENT_BASIC_INTERNAL_TOKEN_CONFIG_PASSWORD=secret
```

**After:**
```bash
OPENTMF_CLIENTS_INTERNAL_BASIC_AUTH_USERNAME=admin
OPENTMF_CLIENTS_INTERNAL_BASIC_AUTH_PASSWORD=secret
```

#### Example: No-auth client "healthcheck"

**Before:** Not possible.

**After:**
```bash
OPENTMF_CLIENTS_HEALTHCHECK_REQUEST_TIMEOUT_MILLIS=5000
OPENTMF_CLIENTS_HEALTHCHECK_RESPONSE_TIMEOUT_MILLIS=10000
```

#### Example: Common properties (unchanged structure, new prefix)

**Before:**
```bash
OPENTMF_WEBCLIENT_OPENID_DNEXT_MAX_CONNECTIONS=100
OPENTMF_WEBCLIENT_OPENID_DNEXT_REQUEST_TIMEOUT_MILLIS=30000
OPENTMF_WEBCLIENT_OPENID_DNEXT_RESPONSE_TIMEOUT_MILLIS=45000
OPENTMF_WEBCLIENT_OPENID_DNEXT_NUM_RETRIES=3
OPENTMF_WEBCLIENT_OPENID_DNEXT_RETRY_WAIT_MILLIS=5000
OPENTMF_WEBCLIENT_OPENID_DNEXT_FIXED_HEADERS_X_COUNTRY_CODE=TR
```

**After:**
```bash
OPENTMF_CLIENTS_DNEXT_MAX_CONNECTIONS=100
OPENTMF_CLIENTS_DNEXT_REQUEST_TIMEOUT_MILLIS=30000
OPENTMF_CLIENTS_DNEXT_RESPONSE_TIMEOUT_MILLIS=45000
OPENTMF_CLIENTS_DNEXT_NUM_RETRIES=3
OPENTMF_CLIENTS_DNEXT_RETRY_WAIT_MILLIS=5000
OPENTMF_CLIENTS_DNEXT_FIXED_HEADERS_X_COUNTRY_CODE=TR
```

#### Example: REST client with bearer auth (new capability)

**Before:** Not possible.

**After:**
```bash
OPENTMF_CLIENT_TYPE=REST                                   # global default (optional, default is REST)
OPENTMF_HTTP_CLIENT_TYPE=JDK                               # global default for REST clients
OPENTMF_CLIENTS_SYNC_API_CLIENT_TYPE=REST                  # override: this client is synchronous
OPENTMF_CLIENTS_SYNC_API_HTTP_CLIENT_TYPE=APACHE           # override: use Apache for this client
OPENTMF_CLIENTS_SYNC_API_BEARER_AUTH_TOKEN_URL=http://keycloak:8080/realms/dsync/protocol/openid-connect/token
OPENTMF_CLIENTS_SYNC_API_BEARER_AUTH_CLIENT_ID=federation
OPENTMF_CLIENTS_SYNC_API_BEARER_AUTH_CLIENT_SECRET=mySecret
OPENTMF_CLIENTS_SYNC_API_BEARER_AUTH_FORM_DATA_GRANT_TYPE=client_credentials
OPENTMF_CLIENTS_SYNC_API_BEARER_AUTH_FORM_DATA_SCOPE=openid
```
