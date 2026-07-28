# Implementation plan — optional resilience4j integration for opentmf-http-clients

**Status:** plan only (2026-07-28, authored for the dnms-rules effort). To be
implemented in a dedicated opentmf session.

**Decision context & recommendation.** The library today offers timeouts,
`num-retries` + `SyncClientUtil.executeWithRetry`, and token-retrieval retry —
but no circuit breaking, no bulkheading, no enforced per-call time budget.
Consumers (dsync adapters; every dnms adapter calling SMTP gateways, OneDMS,
ASGW, DXL) face flaky externals where retry-alone amplifies outages instead of
containing them. **Recommendation: YES, add resilience4j — as an OPTIONAL,
config-driven decoration inside this library**, because (a) the library already
owns the per-`<id>` client model, so per-client resilience config is a natural
extension of the existing prefix; (b) doing it here once beats N hand-rolled
wrappers in consumers; (c) resilience4j is dependency-light (core modules have no
Spring requirement) and supports both sync and reactive. Off by default — zero
behavior change for existing consumers.

---

## 1. Scope — all client types the library builds

| Client type | Built by | Decoration point |
|---|---|---|
| `RestClient` (`<id>RestClient`) | `-rest` starter path | `ClientHttpRequestInterceptor` executing the exchange inside CircuitBreaker + Bulkhead + (optional) TimeLimiter-equivalent |
| `RestTemplate` (`<id>RestTemplate`) | same sync path | same interceptor type (both accept `ClientHttpRequestInterceptor`) |
| `WebClient` (`<id>WebClient`) | `-reactive` starter path | `ExchangeFilterFunction` applying `CircuitBreakerOperator`/`BulkheadOperator`/`timeout()` from `resilience4j-reactor` |
| Token retrieval (`<id>TokenService`) | common | wrap the token HTTP call with the SAME instances as its owning client (a broken IdP must open the circuit too), keeping the existing token-retry semantics |

One resilience configuration → applied uniformly to whichever client shapes the
`<id>` exposes. `TmfClient`s (opentmf-api-clients) inherit it transitively via
their `client-ref` — no change needed in that repo.

## 2. Dependencies & module layout

- `opentmf-http-clients-common`: **optional** deps `resilience4j-circuitbreaker`,
  `resilience4j-bulkhead`, `resilience4j-timelimiter`, `resilience4j-micrometer`
  (all core, no Spring). `-reactive` additionally: optional
  `resilience4j-reactor`.
- NOT `resilience4j-spring-boot3` — we wire registries ourselves inside the
  existing autoconfiguration; the Spring starter would fight the library's
  programmatic bean registration model and drag config surfaces we don't want.
- Activation = classpath + property gated:
  `@ConditionalOnClass(CircuitBreaker.class)` +
  `opentmf.http-clients.<id>.resilience.enabled=true`. Absent jar or absent
  property ⇒ today's behavior, byte-for-byte.
- Versions BOM-managed: add resilience4j to `opentmf-versions`.

## 3. Configuration surface (per client id, mirrors existing style)

```yaml
opentmf:
  http-clients:
    onedms:
      base-url: https://...
      num-retries: 3                      # existing, unchanged
      resilience:
        enabled: true                     # default false
        circuit-breaker:
          failure-rate-threshold: 50      # %
          slow-call-rate-threshold: 100   # %
          slow-call-duration-threshold: 5s
          sliding-window-size: 50
          minimum-number-of-calls: 20
          wait-duration-in-open-state: 30s
          permitted-calls-in-half-open: 5
          record-status-codes: [500, 502, 503, 504]   # 4xx are caller bugs, NOT breaker food
        bulkhead:
          max-concurrent-calls: 25        # 0/absent = disabled
          max-wait-duration: 0s
        time-limiter:                     # reactive only; sync uses connect/read timeouts
          timeout-duration: 10s
```

Bind into `ClientProperties` as a nested `ResilienceProperties` (defaults in the
class per the no-`${ENV:default}` rule). One `CircuitBreakerRegistry`/
`BulkheadRegistry` pair per application context, instances named `<id>`.

## 4. Semantics (the design decisions)

1. **Order:** Bulkhead → CircuitBreaker → timeout → HTTP call. Retry
   (`executeWithRetry`) stays OUTSIDE and above all of it — a retry against an
   OPEN circuit fails fast with `CallNotPermittedException`, which is the point.
2. **What counts as failure:** `IOException`/timeouts + the configured
   `record-status-codes`. Client-side 4xx never trips the breaker. 404 stays a
   normal outcome (`emptyOn404` unaffected).
3. **Error mapping:** `CallNotPermittedException` and `BulkheadFullException`
   surface wrapped in the library's existing client-exception hierarchy with a
   distinguishable cause, so consumers/advice can map them (dnms maps both to 503
   upstream-degraded).
4. **`SyncClientUtil.executeWithRetry` integration:** teach it to NOT retry on
   `CallNotPermittedException` (an open circuit means "stop calling" — retrying
   defeats it). Everything else about the util unchanged.
5. **Metrics:** `resilience4j-micrometer` binders registered when a
   `MeterRegistry` bean exists — `resilience4j.circuitbreaker.state{name="<id>"}`
   etc. land on the standard Prometheus scrape with zero consumer work.
6. **Token service:** shares the owning client's instances (see §1) — prevents
   the "IdP down, every pod hammers the token endpoint" stampede.

## 5. Work breakdown

1. `ResilienceProperties` + binding + validation (+ docs in README). *(half day)*
2. Registries + instance lifecycle in `opentmf-http-clients-autoconfigure`,
   conditional wiring. *(half day)*
3. Sync interceptor (`RestClient` + `RestTemplate`) + unit tests. *(1 day)*
4. Reactive `ExchangeFilterFunction` + tests. *(1 day)*
5. Token-service wrapping + `executeWithRetry` open-circuit awareness + tests. *(half day)*
6. Micrometer binding + IT proving metrics appear. *(half day)*
7. ITs in `opentmf-http-clients-tests`: breaker opens on injected 503 storms
   (mockserver `respondSequence`), bulkhead saturation, half-open recovery,
   disabled-by-default regression suite. *(1 day)*
8. README + README_MTLS cross-note + CHANGELOG + BOM addition; release. *(half day)*

## 6. Acceptance criteria

- `resilience.enabled` absent ⇒ zero behavior/dependency-graph change (proved by
  the existing test suite passing untouched).
- One YAML block per client id yields breaker+bulkhead+metrics on ALL client
  shapes of that id, including its token calls.
- Open circuit fails fast; `executeWithRetry` does not retry it.
- Coverage gate per opentmf standards; javadoc on every public type.
