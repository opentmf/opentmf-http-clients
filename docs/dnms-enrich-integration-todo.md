# TODO — dnms-enrich dynamic-source integration

> **Status 2026-07-30: ALL FOUR ITEMS IMPLEMENTED** (opentmf-http-clients 2.1.4-SNAPSHOT).
> #1 → `HttpClientRegistry` (getOrCreate/replace/evict + reactive variants, 30s grace-period
> close, per-type close semantics as decided below, resilience reset on swap).
> #2 → `ClientPropertiesValidator.validate(type, props)` — all findings, side-effect-free.
> #3 → `opentmf.client.pool.*{client=<name>}` gauges for Apache clients (auto-registered,
> removed on evict).
> #4 → resolved via the validator finding: JDK + max-connections without a resilience bulkhead
> is flagged, pointing to `client-type: apache` or `resilience.bulkhead.max-concurrent-calls`
> (the resilience4j integration landed in the same release, so the semaphore option exists
> as the bulkhead).

Gaps identified while designing dnms-enrich's dynamic enrichment-source
client management (DNMS / dnotify-analysis, 2026-07-10). Context: dnms-enrich
builds HTTP clients **programmatically at runtime** from catalog rows
(`enrichment_source`: endpoint, auth_scheme, scope, Vault bundle ref,
max_concurrency, client_profile) — tens of sources, registered/changed by
SREs without redeployment. Static `opentmf.http-clients.*` clients keep
working unchanged (dnms-enrich also uses them for Keycloak + dnms-catalog).

## 1. Registry-style client lifecycle API  ← the one blocker

**Problem:** `RestClientRegistrar.registerBeans(...)` registers Spring beans
via `registerIfAbsent` — create-once. A changed source row cannot hot-swap
its client, and bean-factory registration is the wrong lifecycle for churning
clients anyway.

**Ask:** a non-bean registry, e.g.

```java
HttpClientRegistry
  RestClient  getOrCreate(String name, ClientType type, ClientProperties props);
  RestClient  replace(String name, ClientType type, ClientProperties props);  // build new, swap, CLOSE old
  void        evict(String name);                                             // close + remove
```

Closing must release the underlying connection manager (Apache CM
`close()`); swap must be safe under concurrent in-flight requests
(old client finishes its calls, then closes).

**Close semantics per client type (decided 2026-07-15 — library stays on
Java 17, no baseline bump):**

- **APACHE** — the recommended type for dynamic/churning clients (see also
  #4): real pool control, `CloseableHttpClient.close(CloseMode.GRACEFUL)`,
  pool metrics. Note graceful close does not *wait* for in-flight requests;
  quiescent swap needs reference counting or a grace-period delayed close.
- **JDK** — `java.net.http.HttpClient` implements `AutoCloseable` only since
  Java 21 (JDK-8304165), and this library compiles against 17. The registry
  must use a guarded close, which compiles on 17 and does a real close
  (waiting for in-flight requests) on 21+ runtimes:

  ```java
  if (httpClient instanceof AutoCloseable closeable) {
    closeable.close();
  }
  ```

  On a 17 runtime the branch is skipped and the abandoned client is
  reclaimed by GC — acceptable, because an un-closed JDK client only pins a
  selector thread until GC. This is also why the *static* client path keeps
  its invariant: clients are long-lived per-`<id>` singletons created at
  startup; never create per-request clients.
- **NETTY** — dispose the client's `ConnectionProvider`
  (`disposeLater()` drains gracefully).

## 2. Expose `ClientProperties.validate()` publicly

**Problem:** dnms-enrich's SRE "test connection" and health check must
validate a programmatically-built configuration (required fields per auth
mode, cert refs present, sane timeouts) — today that logic is internal to
the factories/auto-config.

**Ask:** a public, side-effect-free `validate()` (or
`ClientPropertiesValidator`) returning ALL findings (not fail-fast), so the
UI can show a checklist.

## 3. Micrometer binder for per-client pool gauges

**Problem:** the DNMS observability posture (Collector-centric, native
Micrometer) needs per-client `active / idle / pending` connection gauges —
the SRE's "raise max-connections, with the source owner's blessing" signal.

**Ask:** an optional `MeterBinder` (tagged `client=<name>`) for the Apache
connection manager; no-op where the client type exposes no pool.

## 4. `max-connections` is unenforceable on the JDK client type

**Problem:** JDK `HttpClient` exposes no connection-pool / per-route limit —
`max-connections` silently means nothing there. dnms-enrich treats
`max_concurrency` as a CONTRACT with the source system's owner.

**Ask (either):**
- document APACHE as the required client type when `max-connections` is set
  (and fail `validate()` on JDK + max-connections), **or**
- add a semaphore-based concurrency limiter for the JDK type so the contract
  holds regardless of client type.

## Explicitly NOT needed (decided caller-side — no library change)

- **Secret indirection**: dnms-enrich populates `ClientProperties` secrets
  (client-secret, passwords, base64-JKS for DXL mTLS) in memory from its
  Vault bundle at build time; literal secrets never sit in any yaml.
- **Client-profile templates**: inert config fragments in dnms-enrich's OWN
  namespace (`dnms.enrich.client-profiles.*`), deliberately outside
  `opentmf.http-clients.*` so the auto-configuration never touches them;
  dnms-enrich merges row > profile > defaults before calling the factory.
- **Runtime max-connections setters**: unnecessary given #1 —
  rebuild-and-swap on row change is the simpler lifecycle.

*Reference: dnotify-analysis — `to-be-resources/dnms-catalog/db/`
(`enrichment_source` comments) and the catalog README's service checks.*
