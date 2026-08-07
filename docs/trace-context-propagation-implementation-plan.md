# Implementation Plan: W3C trace-context (`traceparent`) propagation on outbound clients

## Overview

The HTTP clients built by `opentmf-http-clients` do **not** propagate W3C trace context on outbound
calls. A caller's server-side trace therefore does not extend across the outbound HTTP hop — the
trace stops at the client and the callee starts a fresh, disconnected trace (or none). End-to-end
traces in Grafana/Tempo (or any collector) are broken for every service that calls out through these
clients.

This plan wires the outbound clients to Micrometer's observation/propagation so that, **when the
consuming application has tracing configured**, a `traceparent` header is emitted automatically —
and, when it does not, nothing changes (graceful no-op, no new hard dependency on a tracer).

Found by dnms-catalog (Vodafone-DE) observability testing; the same gap is present in dsync. The
internal tracing layer of consumers is otherwise correct (Micrometer-tracing → OTLP, trace ids in
MDC/logs, W3C propagation on Kafka) — only the **outbound HTTP** hop drops the context.

## Relationship to `http-response-headers.md` §4.4 (distinct, complementary)

`docs/http-response-headers.md` §4.4 already treats `X-Request-Id` / `X-Correlation-Id` / `traceparent`,
but from the **response-reading / diagnostics** angle: capture the *downstream's* identifier off *its
response* and copy it into the exception message and error log so a failed vendor call becomes an
actionable support ticket. That is **reactive** and about the *callee's* id.

This plan is the other direction: **proactively SEND our own trace context on the outbound REQUEST** so
the downstream *continues our distributed trace* (one trace spanning the hop in Grafana/Tempo). It is a
different mechanism (Micrometer client observation → `traceparent` on the request, not a header read),
a different value (end-to-end trace continuity, not vendor-id capture), and neither duplicates the
other. They compose: §4.4 records who the callee was on failure; this plan makes the callee part of the
same trace to begin with.

## Root cause

**Synchronous (`RestTemplate`) — no observation wiring at all.** Both REST factories construct the
`RestTemplate` by hand and attach only fixed-header / gzip / Logbook interceptors. They bypass Spring
Boot's auto-configured `RestTemplateBuilder`, so the `ObservationRestTemplateCustomizer` — the
customizer that installs the client observation (and, with a tracer present, the propagation
interceptor) — is never applied. No `ObservationRegistry` is set anywhere.

| File | Line | Evidence |
|---|---|---|
| `opentmf-http-clients-autoconfigure/.../rest/ApacheRestTemplateFactory.java` | 97 | `new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient))` — then only `addFixedHeadersInterceptor` (99) + `addLogbookInterceptor` (100) |
| `.../rest/JdkRestTemplateFactory.java` | 56 | `new RestTemplate(requestFactory)` — then gzip (60) + fixed-headers (62) + logbook (63) |

A grep of the REST starter for `ObservationRegistry` / `setObservationRegistry` / `RestTemplateBuilder`
/ `ObservationRestTemplate*` returns nothing.

**Reactive (`WebClient`) — depends on the injected builder, registry never set explicitly.**
`WebClientConfigUtil.createWebClient(WebClient.Builder, HttpClient, props)` (lines 87–102) configures
`.clientConnector(...)` on the **injected** builder and calls `.build()`, but never calls
`.observationRegistry(...)`. Whether it propagates therefore hinges on whether
`BasicWebClientProviderImpl` injects Boot's auto-configured (observation-aware) `WebClient.Builder`
bean or a bare `WebClient.builder()`. This must be verified and made explicit.

## Scope

- `opentmf-http-clients-autoconfigure` — `ApacheRestTemplateFactory`, `JdkRestTemplateFactory` (+ the
  autoconfig that constructs them).
- `opentmf-http-clients-reactive` — `WebClientConfigUtil` / `BasicWebClientProviderImpl`.

## Design

Make every outbound client **observation-aware** by handing it the application's `ObservationRegistry`
bean. Spring Boot always publishes an `ObservationRegistry` (a no-op when nothing is configured), and
the actual `traceparent` emission is contributed by Micrometer-tracing's
`PropagatingSenderTracingObservationHandler`, which is registered on that registry only when the
consumer has a `Tracer` + propagator on the classpath. Consequences:

- **Opt-in / graceful:** no tracer configured → the registry is effectively no-op → no `traceparent`,
  no behavioural change. `opentmf-http-clients` therefore does **not** need a hard dependency on
  `micrometer-tracing`; it only needs the observation API (`micrometer-core`, already a dependency of
  the reactive module — add it to the sync autoconfigure module if absent).
- **No consumer API change** — the `{ref}RestClient` / WebClient beans keep their signatures; only
  their internal wiring gains the registry.

## Detailed changes

1. **`ApacheRestTemplateFactory`** — inject `ObservationRegistry`; call
   `restTemplate.setObservationRegistry(observationRegistry)` immediately after `new RestTemplate(...)`
   (line 97) and **before** `addFixedHeadersInterceptor` / `addLogbookInterceptor`. `RestTemplate`
   has `setObservationRegistry(ObservationRegistry)` since Spring Framework 6.0 (the lib is on
   Framework 7). Keep the hand-built request factory and the existing interceptors unchanged.
2. **`JdkRestTemplateFactory`** — the same `setObservationRegistry(...)` after `new RestTemplate(...)`
   (line 56), before the gzip/fixed-header/logbook interceptors.
3. **Autoconfigure wiring** — pass the `ObservationRegistry` bean into both factories (constructor or
   factory-method parameter). It is always resolvable from the context.
4. **`WebClientConfigUtil.createWebClient`** — set `webClientBuilder.observationRegistry(observationRegistry)`
   on the injected builder before `.build()` (line 102). Additionally confirm `BasicWebClientProviderImpl`
   injects Boot's auto-configured `WebClient.Builder` bean (not a bare `WebClient.builder()`); if it
   already does, the explicit `.observationRegistry(...)` is a safe belt-and-braces guarantee.
5. **Dependencies** — ensure `io.micrometer:micrometer-core` is on the **sync** autoconfigure module's
   classpath (the reactive module already declares it: `opentmf-http-clients-reactive/pom.xml`).

## Testing

- **Propagation ON:** with a test `ObservationRegistry` carrying a `PropagatingSenderTracingObservationHandler`
  (or a real Micrometer-tracing test setup), make an outbound call through each client (Apache
  RestTemplate, JDK RestTemplate, WebClient) against a mock server, and assert the captured request
  carries a well-formed `traceparent` (and, if configured, `tracestate`).
- **Graceful OFF:** with a no-op `ObservationRegistry` (no tracer), assert **no** `traceparent` is
  sent and the call otherwise behaves exactly as today (existing interceptors, headers, error
  wrapping unchanged).
- Keep the existing factory/IT suites green — the change is additive to the interceptor/observation
  chain, not a replacement.

## Backward compatibility & rollout

- Additive, no public API change. Ship as a **patch/minor** release on `develop` → the usual
  maven-release-plugin flow.
- Consumers that already run Micrometer-tracing get outbound `traceparent` transparently; consumers
  without tracing are unaffected (no-op registry).
- Closes the outbound half of the observability gap found in dnms-catalog; dnms-catalog handles the
  inbound/response-surfacing half locally (a response `X-Trace-Id` header + a `traceId` on the
  RFC-7807 `ProblemDetail`) — a candidate to promote into a shared `opentmf-web`/observability starter
  later so the inbound side is standardised too.

## Open questions for review

1. **Sync module dependency:** does the sync autoconfigure module already resolve `micrometer-core`
   transitively, or should it be declared explicitly?
2. **Reactive builder source:** is `BasicWebClientProviderImpl`'s injected `WebClient.Builder` Boot's
   observation-aware bean? If yes, step 4 is a safety net rather than the actual fix.
3. **Always-on vs flag:** recommend always-on when an `ObservationRegistry` is present (it is the
   expected, standard behaviour and is a no-op without a tracer); a config flag seems unnecessary but
   is trivial to add if a deployment wants to force outbound propagation off.
