# Changelog

All notable changes to this project will be documented in this file.

This project is the successor to [opentmf-web-clients](https://github.com/opentmf/opentmf-web-clients) (v1x).
For migration guidance from the predecessor, see the [Migration from opentmf-web-clients](README.md#migration-from-v1x) section in the README.

## [2.6.0] - 2026-11-21

### Added
- **`RestClientFactory`, beside `RestTemplateFactory`.** Spring Framework 7.1 marks
  `RestTemplate` and its `ResponseErrorHandler` for removal, so an application needs a way to
  build and detect the library's synchronous clients without either. The JDK and Apache backends
  now implement `RestClientFactory` (`createRestClient(clientId, properties)`) as well as
  `RestTemplateFactory`; an application can use it as its `@ConditionalOnBean` marker in place of
  `RestTemplateFactory`. `HttpClientRegistry` gains `getOrCreateRestClient` and
  `replaceRestClient`, which return a `ManagedRestClient` (the `RestClient` and the token
  service, no `RestTemplate`); under one name they see the same client as `getOrCreate` and
  `replace`. `RestClientRegistrar` gains `createRestClient(clientId, type, properties)`,
  `createPipeline(clientId, type, properties)` and `createTokenService(clientId, pipeline)`.
- **New meter series on a service whose `MeterRegistry` comes from Boot's metrics
  auto-configuration:** `opentmf.client.token.fetch`, `opentmf.client.pool.leased|available|
  pending|max` and the `resilience4j.circuitbreaker.*`/`resilience4j.bulkhead.*` meters of the
  clients now appear on its scrape, where they did not before (see "The library's meters were
  never registered" under Fixed). A dashboard or an alert on these names may start to see data.
- **`peer-text-in-message`: how much of a peer's error text the exception message carries**, per
  client: `whole` (the default), `capped` or `none`. The message of an
  `OpenTmfClientResponseException` takes the error field of a JSON body whole, whatever its
  length; a field that is an object or an array goes in as its whole JSON text. Only a non-JSON
  body was cut, at 2000 characters. So an application that logs the message, puts it into its
  own answer or stores it carried a peer's text of any length. `capped` cuts the peer's text at
  2000 characters (or `peer-text-cap-length`, below) followed by `...`, the joined fields and a non-string field's JSON text alike.
  `none` keeps `HTTP <status> <reason>` only. **Compatibility:** unset, or `whole`, gives the
  message of 2.5.1 byte for byte. The golden over string, object, array, non-JSON, binary and
  empty bodies was taken on the message code as released in 2.5.1, unchanged until this change.
- **`peer-text-cap-length`: where the exception message cuts a peer's text**, per client,
  default 2000. It sets the cut of `capped` and the cut of a non-JSON body, which was always 2000.
  It lies between 100 and 100000; a value outside them stops the application at start with
  `peer-text-cap-length must be between 100 and 100000`. **Compatibility:** without the setting the
  message is the message of 2.5.1. New overloads taking a client's `ClientProperties` follow both
  settings: `OpenTmfResponseErrorHandler(ClientProperties)`,
  `OpenTmfRestClientStatusHandler.errorHandler(ClientProperties)` and
  `WebClientConfigUtil.errorWrappingFilter(ClientProperties)`; the library installs them on every
  client it builds. `ErrorBodyExtractor.extractMessage` gains overloads with the cap length. The
  entry points without properties (`new OpenTmfResponseErrorHandler()`, `errorHandler()`,
  `errorWrappingFilter()`, `createRestClient(RestTemplate)`) keep the message of 2.5.1 and ignore
  both settings.
  It holds on every client type through `RestTemplate`, `RestClient` and `WebClient`. In every
  value `getResponseBody()`, the status, the headers, `Retry-After` and the `404` subtype are
  unchanged. The no-argument `OpenTmfResponseErrorHandler()`,
  `OpenTmfRestClientStatusHandler.errorHandler()`, `WebClientConfigUtil.errorWrappingFilter()`,
  `RestClientRegistrar.createRestClient(RestTemplate)` and the existing
  `ErrorBodyExtractor.extractMessage` methods keep today's message. Each has a new overload that
  takes the value. An application that reads the peer's sentence out of the message must not
  choose `none`; `getResponseBody()` holds it in every value.
- **`bearer-auth.failure-memory`: an opt-in memory of a failed token retrieval**, unset by
  default (and then nothing is remembered, as before). When set, a retrieval that failed
  retryably (a 5xx, `408` or `429` from the token endpoint, or a transport failure, after the
  token call's own retries) is remembered for that long: until then every token request for the
  same key fails at once with that failure (its own copy), without a call. A `Retry-After` from
  the token endpoint lengthens it, up to the client's `max-retry-after`. A refused credential
  (any other 4xx) is remembered only by `bearer-auth.refusal-memory`, and an unusable answer is
  never remembered; `clearCache()` forgets. The cost: once the identity provider is back, the
  next token can come up to that long late.
- **`bearer-auth.refusal-memory`: an opt-in memory of a refused credential**, unset by default
  (and then nothing is remembered, as before). When set, a retrieval that the token endpoint
  refused (a 4xx other than `408` and `429`, e.g. a wrong client secret answered `401`) is
  remembered for exactly that long; a `Retry-After` does not lengthen it. Until then every token
  request for the same key (token URL, scope and username) fails at once with that refusal (its
  own copy), without a call; other keys and other clients still call. Independent of
  `failure-memory`. `clearCache()` forgets it. The cost: once the credential is fixed, the next
  token can come up to that long late. `TokenMints` has a constructor that takes it; the
  existing ones remember no refusal.
- `TokenMints` (`opentmf-http-clients-bearer-provider`): the one mint per key and the failure
  memory of a token service; and the constructors
  `SyncBearerTokenServiceImpl(config, cache, client, maxRetryAfter)` and
  `BearerTokenServiceImpl(config, cache, client, maxRetryAfter)`, which bound the failure
  memory's `Retry-After` by the client's `max-retry-after` (the existing constructors use its
  default, 30 s). `BearerTokenUtil.TOKEN_RETRY_JITTER` names the jitter.
- `bearer-auth.log-response-body` (default `false`): `true` gives the token calls' log line its
  response body again, for debugging a token endpoint.
- `BearerTokenException.getTokenUrl()` / `getOauthError()` and the constructor
  `BearerTokenException(URI tokenUrl, HttpStatusCode status, String oauthError)`; the existing
  constructors are unchanged.
- `TokenStatusFailures` (`opentmf-http-clients-bearer-provider`): turns a status error of a token
  call into its `BearerTokenException`, for code that wires the token clients by hand.
- `TokenUrls` (`opentmf-http-clients-common`): `isUsable(URI)`, `shown(URI)`, `isShownAsItIs(URI)`
  and `scrubbed(String, URI)`, how the library judges and shows a token URL.
- `BearerAuthRequirements.requireTokenUrl(clientId, properties)`
  (`opentmf-http-clients-common`): the refusal above, for code that builds clients by hand.
- **`exchange-timeout`: an opt-in bound on the whole exchange**, from the send to the last byte
  of the body. Unset by default, and **a client that does not set it behaves exactly as before**.
  On `apache` and `netty`, where `response-timeout` is silence per read, it cuts a body that keeps
  arriving, which nothing cut until now (measured: a body trickling one byte every 700 ms completes
  after 4.9 s under a 2 s `response-timeout`; with a 3 s `exchange-timeout` it is cut at 3.0 s); a
  stall is still cut by `response-timeout` when that comes first. On `jdk`, where
  `response-timeout` already is one deadline for the whole exchange, the smaller of the two is
  that deadline. A cut fails as the client's transport failure, never as an answer: on `apache` the
  body-extraction `RestClientException` or the `ResourceAccessException`, on `netty` a
  `WebClientRequestException`, each caused by a `SocketTimeoutException` that names the bound
  ("exchange-timeout of 3000 ms passed before the response was fully received"); on `jdk` the
  JDK client's own timeout. With it set, a `netty` body that
  fails for another transport reason arrives as a `WebClientRequestException` too, not as the
  `WebClientResponseException` with the answer's `200`. The bearer token call runs under the
  client's `exchange-timeout` as well. See the README's "What `exchange-timeout` bounds".
- `ExchangeTimeouts` (`opentmf-http-clients-common`): builds the `SocketTimeoutException` an
  `exchange-timeout` raises. Its message is informational, not a contract to match on: a caller
  cannot tell from the exception which of `exchange-timeout` and `response-timeout` cut the
  exchange.
- **A bearer token request can have bounds of its own:** `bearer-auth.request-timeout`,
  `bearer-auth.response-timeout` and `bearer-auth.num-retries`. Until now a token request always
  ran on the client's own `request-timeout`, `response-timeout` and `num-retries`, so a client tuned
  for a slow backend (say 120 s and three retries) gave the identity provider the same bounds, and
  could not lower them without changing its own business calls' retries. **Each key defaults to the
  client's own value: a client that sets none of them behaves exactly as before.** The client's
  API calls keep the client's bounds whatever is set; the token request still shares the client's
  connection pool, TLS, proxy, interceptors and resilience. On every client type (`jdk`, `apache`,
  `netty`) and for dynamic clients. See the README's "Token request bounds".
- `RestClientRegistrar.createTokenService(clientId, restTemplate, restClient, properties)`: the
  token request's own timeouts are applied over the given `RestTemplate`'s HTTP client. The
  overloads without the `RestTemplate` keep working; setting `bearer-auth.request-timeout` or
  `bearer-auth.response-timeout` with them fails by name.
- `TokenCallProperties.of(ClientProperties)` (`opentmf-http-clients-common`): the settings a
  token request runs on, for code that wires the token clients by hand.

### Changed
- **A token fetch's client observation is labelled `uri=token-fetch`.** The token call goes to
  the configured token address, not a URI template, so its `http.client.requests` metric and span
  carried `uri=none`, and a dashboard could not tell it from the client's other untemplated calls.
  Every token client — `jdk`, `apache` and `netty`, static and dynamic — now labels it with the
  fixed route name `token-fetch` (`TokenRoute.NAME`), never the address. Nothing else changes: the
  observation's name, its other labels and the client's own calls stay as they were. A dashboard
  or alert that selected token calls by `uri="none"` selects them by `uri="token-fetch"` now.
- **The `RestClient` is built from the client's parts, no longer from its `RestTemplate`.** The
  library builds each synchronous client's parts once — the HTTP client with its mTLS, pool and
  time limits, the interceptors in their order, the observation registry, the base URL and the
  error handling — and makes the `{clientId}RestClient` from them directly; the
  `{clientId}RestTemplate` is made from the same parts. Both beans are still registered, share
  one connection pool, and behave as before: the same interceptors in the same order, the same
  Logbook output, the same circuit breaker, the same mTLS and proxy, the same `traceparent`, the
  same exceptions.
- **A failed token call is its own type, with the identity provider's body withheld.** An error
  status from the token endpoint (any 4xx or 5xx) surfaced as the same exception as a failure of
  the call the token was for — `OpenTmfClientResponseException`, `OpenTmfClientNotFoundException`
  for a `404` — with the identity provider's whole body in `getResponseBody()`, so a consumer could
  not tell a token endpoint's `400`, `404` or `410` from the answer of the service it called, and a
  provider that echoes the request form put a credential into the exception. It now throws
  `BearerTokenException`, on every client type (`jdk`, `apache`, `netty`), static and dynamic
  clients alike. **Compatibility, measured on the released 2.5.1 jars against 2.6.0 (each client
  type, the token endpoint answering `400`, `404`, `410` and `503`):**
  - a catch of `OpenTmfClientResponseException` catches it as before (`BearerTokenException` is
    one); status, response headers, `Retry-After` and the number of token requests (a `503`
    1 + `num-retries`, others 1) are unchanged;
  - `getResponseBody()` is `null` (2.5.1: the whole body, the echo included), and the message is
    `Bearer token request to <token-url> answered 400 Bad Request (error=invalid_client)`
    (2.5.1: `HTTP 400 Bad Request: <error_description>`); `getOauthError()` gives the RFC 6749
    error code;
  - a token endpoint's `404` is a `BearerTokenException` and **no longer an
    `OpenTmfClientNotFoundException`** (2.5.1: it was): a wrong token URL, not an absent resource.

  Every token call that got an answer and failed is now one of two sibling types, both
  `OpenTmfClientResponseException`s: `BearerTokenException` (an error status) and the unchanged
  `BearerTokenResponseException` (a success status without a usable token); a consumer that tells
  them from the target's answer names both. See the README's "A failed token call since 2.6.0".
- **A client with `bearer-auth` but no `bearer-auth.token-url` is refused when it is built**, by
  name (`Client '<id>' has bearer-auth but no bearer-auth.token-url. …`), at context start and in
  the dynamic-client registry, whether or not a bean validator is on the classpath. A
  `bearer-auth` block is bound as soon as any of its keys is set, so a sub-key alone made a bearer
  client; with a bean validator it already failed at start, without one it failed at its first
  token call with a `NullPointerException` (measured on 2.5.1). `bearer-auth.use-mock: true`
  needs no token URL, as before.
- **A `bearer-auth.token-url` that is not an absolute `http` or `https` URL with a host is
  refused when the client is built**, by name and without quoting the URL (`Client '<id>' has a
  bearer-auth.token-url that is not an absolute http or https URL with a host.`): a misspelt
  scheme, a space in the host, an empty host. Measured on 2.5.1: such a client started and
  failed at its first token call, with an untyped `IllegalArgumentException` on `jdk` and a
  `BearerTokenTransportException` after a second attempt on `apache` and `netty`.
- **A user part, a query or a fragment of the token URL is no longer shown.**
  `http://user:password@idp/token` and `https://idp/token?p=policy` are accepted and work as
  before (on `jdk` and `netty` the user part is never sent; `apache` refuses it at the call, as
  before; a query is sent as configured), but the log lines of the token calls (`INFO` minted,
  `DEBUG` before a fetch, transport `WARN`), the messages and `getTokenUrl()` of
  `BearerTokenException`, `BearerTokenResponseException` and `BearerTokenTransportException`, and
  the URL handed to a `TokenFetchListener` show the URL as its scheme, host, port and path only.
  Measured on 2.5.1: the `INFO` line of every mint printed the whole URL, and `getTokenUrl()`
  returned it; code that read `getTokenUrl()` or the listener's URL as the configured URL now gets
  it without those parts. With such a URL a `BearerTokenTransportException` chains no cause,
  since the HTTP client's own exceptions quote the URL with them; its message keeps the cause
  chain (class names and texts) without them. Logbook's line of a token call keeps the query,
  filtered by the application's `logbook.obfuscate.parameters` (README).
- **`WebClientUtil.handleError` logs the request's path only** in its `DEBUG` line (it logged the
  whole URL, a user part and a query included). The library itself does not call it.

- **Logbook logs a token call without the token endpoint's response body.** With Logbook on the
  classpath and `logging-enabled` (the default), the TRACE line of a token call showed the
  identity provider's whole answer through Logbook's own body filters, which mask the tokens of a
  JSON answer but not, say, an echoed `client_secret`. A marker,
  `<token response body withheld; bearer-auth.log-response-body: true logs it>`, now stands in
  its place; nothing else of the line changes (the request with Logbook's own masking, the status,
  the headers), and the client's own calls keep their bodies. **Measured on the released 2.5.1
  jars against 2.6.0**, on all three client types, an identity provider echoing `client_secret` in
  a `400` and in a `200`: on 2.5.1 the echo is in the TRACE line, on 2.6.0 it is not. Scoped to the
  token calls; it neither relies on nor changes the application's Logbook configuration.
- **Documented: what `response-timeout` bounds on each client type**, which no earlier release
  said correctly (the Javadoc called it a read timeout on all client types). The behaviour itself
  is unchanged. On `jdk` it is one deadline for the whole exchange, counted from the send, so a
  body that is still arriving when it passes is cut, a long download included. On `apache` and
  `netty` it is silence per read, so a stalled body is cut but a body that keeps arriving is
  never cut. The README says what the caller sees when a body is cut, and how a caller bounds a
  whole call; `ResponseTimeoutIT` measures both a stalled and a trickling peer on every client
  type. It also names a trap the measurement found: on `netty` a cut body arrives as a
  `WebClientResponseException` carrying the answer's own `200`, so tell it from an error answer by
  its cause, not by its type.
- **Concurrent callers of one token key make one token call, not one each.** A bearer token was
  cached only once its call succeeded, and the lookup, the call and the store were separate steps:
  every caller that found no token, on a cold cache, when the cached token expired under load or
  while the identity provider was failing, made a token call of its own, with its own retries.
  Measured at a stand-in token endpoint with K = 1 / 8 / 32 concurrent callers, sync and reactive
  alike: a `503` outage cost 4 / 32 / 128 calls, a transport outage 2 / 16 / 64, a recovery on an
  empty cache 1 / 8 / 32, a normal expiry under load 1 / 8 / 32. Now a caller that finds no cached
  token while a mint of its key (token URL, scope, username) is in flight waits for that mint and
  shares its outcome: one mint, whatever K (4, 2, 1 and 1 calls). A failure is shared only by the
  callers waiting for that mint; the next caller mints again, unless `bearer-auth.failure-memory`
  is set. **What a caller sees change:** the token counter (`opentmf.client.token.fetch`) and the
  `Bearer token minted` line count once per burst of callers, not once per caller (still once per
  mint, as documented); the mint runs on the thread, and in the trace, of the caller that started
  it, so a waiting caller's own trace holds no token call, only the wait inside its own call; a
  waiting caller that fails gets its own copy of the failure (same type, message, status, headers
  and cause, its own stack trace); a reactive caller that cancels stops waiting, and the mint
  finishes and is cached for the next caller. A single caller sees no change (the compatibility
  report in `docs/token-single-flight-compatibility.md`). See the README's "Concurrent callers and
  a failing identity provider".
- **The token call's status retries are jittered:** each wait is the backoff spread at random by
  up to half of it either way, so the token calls of callers, keys and pods that failed together
  do not retry together. The number of retries and the `Retry-After` handling are unchanged.

- **The `RestTemplate` entry points stay supported; `RestClientFactory` is the recommended one.**
  Spring Framework 7.1 marks `RestTemplate` for removal. The library's `RestTemplate` types and
  methods — `RestTemplateFactory`, `OpenTmfResponseErrorHandler`,
  `HttpClientRegistry.ManagedSyncClient` with `getOrCreate` and `replace`, the `RestTemplate`
  methods of `RestClientRegistrar` and `RestLogbookSupport`, and the backends' protected helpers
  taking a `RestTemplate` — are not marked deprecated and keep working unchanged; their Javadoc
  names the newer entry point. An application that keeps using `RestTemplate` changes nothing. The
  README's "Moving off `RestTemplate`" table names each newer entry point.

### Fixed
- **The library's meters were never registered in an application whose `MeterRegistry` comes
  from Boot's metrics auto-configuration** (actuator or `spring-boot-micrometer-metrics`; every
  service that does not declare its own registry). The starter's auto-configuration was not
  ordered after Boot's, so its "a `MeterRegistry` bean exists" conditions were evaluated before
  the registry was defined. The token counter, the Apache pool gauges and the resilience4j binder
  were then left out without a word. Seen on two stages' live series, one under a load test:
  none of the five meters on any service. The tests had handed the registry in as the
  application's own bean, which is seen before every auto-configuration, and so did not show it.
  The auto-configuration now runs after `MetricsAutoConfiguration` and
  `CompositeMeterRegistryAutoConfiguration`. An application that declares its own
  `MeterRegistry` bean sees no change.
- **A response body cut short no longer reaches the caller as an empty success when Logbook logs
  the call.** Logbook's REST interceptor reads the whole body inside the exchange to log it and
  drops a failure of that read with a WARN ("Unable to log response"). On `apache`, with the
  Logbook logger at `TRACE`, the caller's read then found the end of the stream: a peer that sent
  its headers and stalled in the body until `response-timeout` gave the caller a `200` with no
  body and no error (measured on 2.5.1). The library's Logbook interceptors (the client's calls
  and its token calls) now make every read of the body after such a failure raise that same
  failure, so the caller gets the `RestClientException` caused by `SocketTimeoutException` it
  gets without Logbook. **The only call that changes is one that today returns an empty success
  for a response never fully received;** a body that arrives completely is unchanged and logged
  as before on every client type, and `jdk` and `netty` already failed such a call. **Visible in
  the interceptor chain:** in a REST client's `RestTemplate` interceptors the guard
  (`BodyReadFailureGuard`) now stands where `LogbookClientHttpRequestInterceptor` stood, with
  Logbook's interceptor inside it; code that inspects the chain by type finds the guard there.

## [2.5.1] - 2026-10-17

### Fixed
- **A token answer without a usable token fails by name.** A success answer from the token
  endpoint that had no `access_token` (the configured `bearer-auth.token-field`) threw a bare
  `NullPointerException`; an empty body did the same on the sync path and made the reactive
  `getToken()` complete **empty**; a body that was not JSON surfaced as a parser error quoting the
  body; and a `null` or blank token was handed back as if it were one. On both token paths
  (`SyncTokenClientImpl`, `BearerTokenClientImpl`) every such answer now throws
  `BearerTokenResponseException` naming the token endpoint, the HTTP status and the token field,
  never the body. It is not retried and nothing is cached. Present since 2.0.0.

### Added
- `BearerTokenResponseException` (`opentmf-http-clients-bearer-provider`), an
  `OpenTmfClientResponseException` with `tokenUrl`, `tokenField` and a `reason` (`EMPTY_BODY`, `NOT_A_JSON_OBJECT`, `FIELD_MISSING`,
  `FIELD_NOT_A_STRING`, `FIELD_BLANK`). Its status is the success status the endpoint sent; map it
  on its type. See the README's token-fetch section.
- `BearerTokenUtil.readTokenResponse`: the shared check, for code that mints tokens by hand.

## [2.5.0] - 2026-10-17

### Changed (behaviour)
- **A client's `fixed-headers` no longer reach its token endpoint.** Until now the bearer-token
  call ran with the client's fixed headers, so an API key meant for the backend (e.g. a gateway's
  `x-api-key`) was also sent to the identity provider. This happened on every client type (`jdk`
  and `apache` through the client's `RestClient`, `netty` through the `<id>Token` `WebClient`) and
  for the dynamic clients of `HttpClientRegistry`, and dates from 2.0.0. The token call now carries
  only its own protocol headers (`Content-Type`, Basic `Authorization` when client-id and
  client-secret are set, `Accept` on `netty`) and the new `bearer-auth.headers`. Everything else the
  sync token call shares with the API calls stays shared: the connection pool, the
  `OutboundInterceptorContribution`s, resilience and Logbook.
  **Migration:** a client whose token endpoint needs one of its fixed headers repeats it under
  `bearer-auth.headers`.

### Added
- **`bearer-auth.headers`** (name → value): headers sent on the token call only, for an identity
  provider that needs a header of its own. They never reach the API calls and cannot replace the
  token call's `Content-Type`, nor its Basic `Authorization` when client-id and client-secret are
  set. See the README section "Headers on the token call".
- `FixedHeadersClientHttpRequestInterceptor` (`opentmf-http-clients-rest`): the sync fixed-headers
  interceptor as a named class (it was a lambda), with unchanged semantics — a fixed header is a
  default, a per-request value wins.
- `WebClientConfigUtil.createTokenWebClient` and `ReactiveClientRegistrar.createTokenWebClient`:
  the client's `WebClient` without its fixed headers, for its token calls.

### Fixed
- **Reactive clients no longer leak into each other.** Every library-built `WebClient` came off one
  shared `WebClient.Builder`, and `WebClientConfigUtil.createWebClient` changed that builder in
  place, so each reactive client also carried the fixed headers, the base URL and the
  error-wrapping filter of every client built before it, its token `WebClient` included. A client
  without a `base-url` resolved its relative URIs against an earlier client's. The builder is now
  cloned per client and the given one is left untouched. Present since 2.0.0.

## [2.4.0] - 2026-10-14

### Added
- **`OutboundInterceptorContribution`: one application interceptor on every sync client.** Declare
  a bean of this new type (in `opentmf-http-clients-rest`; it wraps a `ClientHttpRequestInterceptor`
  and an order) and the library puts the interceptor at the head of the chain of every
  `RestTemplate` / `RestClient` it builds: the `<id>RestTemplate` and `<id>RestClient` beans, the
  token calls of `<id>TokenService`, and the sync clients of `HttpClientRegistry`. It runs ahead of
  the library's resilience, gzip, fixed-headers and Logbook interceptors. Several contributions run
  in ascending order, and an `@Order` on the `@Bean` method takes precedence. A contribution that
  throws stops the call before anything goes on the wire, and the circuit breaker does not count
  it. Without such a bean the clients are built as before. The reactive (`WebClient`) clients do
  not take contributions. See the README section "An interceptor on every client".

### Changed
- `JdkRestTemplateFactory` and `ApacheRestTemplateFactory` (Spring `@Configuration` classes) take
  one more constructor argument, `ObjectProvider<OutboundInterceptorContribution>`. This matters
  only to code that constructs them by hand.

## [2.3.0] - 2026-10-08

### Added
- **Sync token clients retry retryable statuses**, like the reactive ones always did: per the
  client's `num-retries` / `retry-wait-duration` / `max-retry-after`, each attempt owning its own
  single transport retry. The library-built sync clients pick this up automatically, so a
  `503`/`429`/… from the token endpoint is now retried (default `num-retries` is 3); set
  `num-retries: 0` to keep the previous behaviour. Hand-wired: the new
  `SyncTokenClientImpl(RestClient, ClientProperties, BearerAuthConfig, TokenFetchListener)`
  constructor; the existing constructors keep the transport retry only.
- `TransportFailures.describeChain(Throwable)`: a cause chain on one line, outermost first.

### Changed
- **The transport-retry `WARN` and the `BearerTokenTransportException` message carry the whole
  cause chain**, not only its root — e.g. `RestClientException: …; caused by IOException: closed;
  caused by IOException: fixed content-length: 200, bytes received: 0; caused by EOFException: …`.
  The root alone could not tell a premature EOF from a reset or an HTTP/2 `GOAWAY`.

### Fixed
- Javadoc of `TokenCacheUtil` linked a Lombok-generated getter javadoc cannot resolve, which made
  the javadoc build report an error (non-fatal).

## [2.2.1] - 2026-10-08

### Fixed
- **The client registrars now condition on their own module, not only on the Spring class.**
  `ReactiveClientRegistrar` was `@ConditionalOnClass(WebClient.class)`, so a servlet-only service
  that carries `spring-webflux` transitively (e.g. via Spring AI's Ollama module) loaded it and
  failed with `NoClassDefFoundError` on the absent `opentmf-http-clients-reactive` classes. It now
  also requires `org.opentmf.client.reactive.service.api.TokenService`; `RestClientRegistrar`
  likewise requires `org.opentmf.client.rest.service.api.SyncTokenService` besides `RestTemplate`.

## [2.2.0] - 2026-09-17

### Added
- **Bearer token mints retry once on a transport-level failure** — sync and reactive token
  clients alike. A keep-alive connection reused after the peer closed it, a reset, a premature
  EOF or an I/O timeout under the token `POST` (headers stage *or* body stage — the
  `IOException: closed` a JDK HttpClient stream throws under Spring's body extractor) is retried
  exactly once, immediately, with one `WARN`. The JDK HttpClient's own stale-connection retry
  covers `GET`/`HEAD` only, so a token mint was left to fail on the first such race. Status errors
  from the token endpoint and open circuit breakers are not retried.
- **`BearerTokenTransportException`** (`org.opentmf.client.bearer.exception`): the typed failure
  a mint throws when the retry fails too — `tokenUrl`, `attempts`, cause = the last failure. Not a
  response exception (no HTTP status exists), so the retry utilities never retry it; map it to 503.
- **Token-fetch observability**: one `INFO` line per successful mint naming the issuer URL, scope,
  attempt and duration; and, with a `MeterRegistry` bean, the counter
  `opentmf.client.token.fetch{client=<id>, outcome=ok|retried|failed}` (Prometheus
  `opentmf_client_token_fetch_total`), one increment per mint. New `TokenFetchListener`
  (`org.opentmf.client.bearer.observe`) carries the same signal to hand-wired token clients;
  `TokenFetchMeters` (`opentmfTokenFetchMeters` bean) is its Micrometer implementation.
- `RestClientRegistrar.createTokenService(String clientId, RestClient, ClientProperties)` overload;
  the 2-arg form stays and simply opts out of the counter.

### Changed
- A mint that fails at the transport level after the retry now throws
  `BearerTokenTransportException` instead of the raw `RestClientException` /
  `ResourceAccessException` (sync) or reactor error (reactive). Status errors are unchanged.
- README: the "only internal retry" note now says what is true — the *reactive* token client retries
  retryable statuses per `num-retries`; the sync token client never did.
- Dependency and build-tooling updates: Spring Boot 4.1.0 → 4.1.1; maven-compiler-plugin 3.15.0 →
  3.16.0, maven-surefire/failsafe 3.5.6 → 3.6.0, maven-deploy-plugin 3.1.4 → 3.2.0,
  sonar-maven-plugin 5.7.0.6970 → 5.8.0.7211.

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
