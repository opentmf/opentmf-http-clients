# Upgrading to 3.0.0

3.0.0 removes the library's `RestTemplate` support. Spring Framework 7.1 marks `RestTemplate`
and its `ResponseErrorHandler` for removal in 8.0. Before 3.0.0 the library's synchronous clients
were conditioned on the `RestTemplate` class and registered a `RestTemplate` bean for every
client, so the day the class goes, even an application that uses only `RestClient` would not
start. 3.0.0 names neither type; its synchronous clients need `RestClient` and the rest module
only.

2.6.0 was never released. This page is measured against 2.5.1, the last release, and against
the 2.5.2 snapshot the services pin (`2.5.2-20261123.055942-11`).

## First: the one change no compiler reports

**A bean conditioned on `RestTemplateFactory` is no longer created, and nothing says so.**
`RestTemplateFactory` is gone. A condition that names it as a class literal
(`@ConditionalOnBean(RestTemplateFactory.class)`) fails to compile, which is the good case. A
condition that names it as a string (`@ConditionalOnBean(type =
"org.opentmf.client.rest.service.api.RestTemplateFactory")`, `@ConditionalOnClass(name = ...)`)
or through a marker type of your own compiles, finds no such bean, and silently leaves your bean
out. Condition it on `RestClientFactory` instead:

```java
@ConditionalOnBean(RestClientFactory.class)   // org.opentmf.client.rest.service.api
```

Both of the library's backends (`jdk`, `apache`) are `RestClientFactory` beans. `RestClientFactory`
exists in the 2.6.0 snapshots, so a consumer can make this move before it takes 3.0.0 and its
guard is never off.

Three more changes surface at run time rather than at compile time, each loudly:

- **The `{clientId}RestTemplate` bean is gone.** A lookup by name (`getBean("catalogRestTemplate")`)
  or an injection with `@Qualifier("catalogRestTemplate")` fails at start. Use
  `{clientId}RestClient`.
- **Two backend classes are renamed, and so are their bean names and logger names.**
  `org.opentmf.client.starter.rest.JdkRestTemplateFactory` is now `JdkRestClientFactory`, and
  `ApacheRestTemplateFactory` is now `ApacheRestClientFactory`, in the same package. Code that
  looks a backend up by its bean name (the class name) or reads its type, and a logging
  configuration or masking inventory that names the old loggers, follows the new names.
- **Two start-up lines change.** `Creating JDK HttpClient RestTemplate for client: <id>` now
  reads `... RestClient ...` (the same for Apache HttpClient 5), and
  `Exposed: <id>RestTemplate` is no longer logged (both at `DEBUG`).

## Removed and renamed, with the replacement

| Removed or renamed in 3.0.0 | Use |
|---|---|
| the `{clientId}RestTemplate` bean | the `{clientId}RestClient` bean |
| `RestTemplateFactory` (as a type, a bean or a `@ConditionalOnBean` marker) | `RestClientFactory` |
| `RestTemplateFactory.create(clientId, properties)`, and `create` on the JDK and Apache backends | `RestClientFactory.createRestClient(clientId, properties)` |
| `JdkRestTemplateFactory` | `JdkRestClientFactory` (same constructor) |
| `ApacheRestTemplateFactory` | `ApacheRestClientFactory` (same constructor) |
| `RestClientRegistrar.createRestTemplate(clientId, type, properties)` | `createRestClient(clientId, type, properties)`, or `createPipeline(clientId, type, properties).restClient()` |
| `RestClientRegistrar.createRestClient(RestTemplate)` and `createRestClient(RestTemplate, ClientProperties)` | `createRestClient(clientId, type, properties)` |
| `RestClientRegistrar.createTokenService(clientId, RestTemplate, RestClient, properties)` | `createTokenService(clientId, pipeline)` with the pipeline of `createPipeline(...)` |
| the `RestClientRegistrar` constructor taking `Map<String, RestTemplateFactory>` | the constructor taking `Map<String, RestClientFactory>` |
| `HttpClientRegistry.ManagedSyncClient` | `HttpClientRegistry.ManagedRestClient` (`restClient()`, `tokenService()`) |
| `HttpClientRegistry.getOrCreate(name, type, properties)` | `getOrCreateRestClient(name, type, properties)` |
| `HttpClientRegistry.replace(name, type, properties)` | `replaceRestClient(name, type, properties)` |
| `RestLogbookSupport.addInterceptor(RestTemplate)` | `addInterceptor(List<ClientHttpRequestInterceptor>)` |
| `OpenTmfResponseErrorHandler()`, `(PeerTextInMessage)`, `(ClientProperties)` | `OpenTmfRestClientStatusHandler.errorHandler()`, `errorHandler(PeerTextInMessage)`, `errorHandler(ClientProperties)` on a `RestClient` (`defaultStatusHandler(HttpStatusCode::isError, ...)`): the same exceptions, the same messages |
| the backends' protected helpers taking a `RestTemplate` (`addContributedInterceptors`, `applyObservationRegistry`, `addResilienceInterceptor`, `addFixedHeadersInterceptor`, `addLogbookInterceptor`) | their `List<ClientHttpRequestInterceptor>` overloads; the pipeline applies the observation registry itself |
| `TokenUtil.REST_TEMPLATE` | none: no bean carries the suffix |

`getOrCreate`, `replace`, `ManagedSyncClient`, `createRestClient(RestTemplate)`,
`createRestTemplate`, `RestTemplateFactory`, `OpenTmfResponseErrorHandler()`,
`RestLogbookSupport.addInterceptor(RestTemplate)`, `TokenUtil.REST_TEMPLATE` and the two backend
classes existed in 2.5.1. The `ClientProperties` and `PeerTextInMessage` overloads,
`createRestClient(RestTemplate, ClientProperties)`, the four-argument `RestClientRegistrar`
constructor and `createTokenService(clientId, RestTemplate, ...)` came with the 2.5.2 and 2.6.0
snapshots only.

## What does not change

- Every configuration key and value, including the `rest` and `servlet` aliases of `jdk`.
- The `{clientId}RestClient`, `{clientId}TokenService`, `{clientId}WebClient` and
  `{clientId}ClientProperties` beans, and how they are built.
- The exceptions (`OpenTmfClientResponseException`, `OpenTmfClientNotFoundException` for a
  `404`, the bearer token exceptions) and `SyncClientUtil`: no `catch` block changes.
- `OutboundInterceptorContribution`, resilience, Logbook, observation and mTLS: the same
  interceptors in the same order on the `RestClient` and its token calls.
- The reactive side: `opentmf-http-clients-reactive` and `opentmf-http-clients-bearer-provider`
  have no incompatible change against either baseline.

## The comparison behind this page

japicmp 0.23.1, protected and public API, incompatible changes only, each module's 3.0.0 jar of
this branch against the same module of the baseline:

```
java -jar japicmp-0.23.1-jar-with-dependencies.jar -o <baseline>.jar -n <3.0.0>.jar \
    --ignore-missing-classes --only-incompatible -a protected
```

| Module | against 2.5.1 | against 2.5.2-20261123.055942-11 |
|---|---|---|
| `common` | `TokenUtil.REST_TEMPLATE` removed | the same |
| `rest` | `RestTemplateFactory`, `OpenTmfResponseErrorHandler` removed | the same, with the `PeerTextInMessage` and `ClientProperties` constructors |
| `reactive` | none | none |
| `bearer-provider` | none | none |
| `autoconfigure` | `HttpClientRegistry.getOrCreate`, `replace`, `ManagedSyncClient`; `JdkRestTemplateFactory`, `ApacheRestTemplateFactory`; `RestClientRegistrar.createRestTemplate`, `createRestClient(RestTemplate)`, the `RestTemplateFactory` constructor; `RestLogbookSupport.addInterceptor(RestTemplate)` | the same, with `createRestClient(RestTemplate, ClientProperties)` and `createTokenService(clientId, RestTemplate, RestClient, properties)` |

Every incompatible change japicmp reports is a removal, and every removal is in the table above.
No method changed its signature, and no class or method was added that replaces another under
the same name.
