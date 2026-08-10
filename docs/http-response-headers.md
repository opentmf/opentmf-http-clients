# Which HTTP response headers should opentmf-http-clients use?

**Status:** analysis / discussion document (2026-08-04). No code changes
implied. Written to be read before deciding what, if anything, lands in
`2.1.6`.

**Question it answers:** of the well-known HTTP response headers, which ones
should this library *read and act on*, which should it merely *expose* to
callers, and which would be *actively harmful* to honour — and in each case,
how far should it go?

> **Decisions taken (2026-08-05).** After review, the agreed scope is
> narrower than the tiers below and supersedes them where they differ:
>
> - **Act on `Retry-After` and `Content-Type` only.** Nothing else in Tier A.
> - **Capture headers on the error path only**, where the caller cannot reach
>   them because we close the response. No success-path exposure — that is the
>   calling application's job via `ResponseEntity`.
> - **No response cache of any kind.** The `Cache-Control` / IMDG design was
>   examined and rejected: RFC 9111 §3.5 forbids a *shared* cache from reusing
>   responses to `Authorization`-bearing requests, so an IMDG-backed variant is
>   either a no-op or a cross-tenant leak; Spring's `CacheManager` has no
>   per-entry TTL (which is why `TokenCacheUtil` already uses raw Caffeine);
>   and token rotation would flush the cache anyway.
> - **One retry authority.** Apache's transport-level automatic retries get
>   disabled so all three backends behave alike.
>
> Shipped in 2.1.6 — see the CHANGELOG entry for what landed, and the
> "Retry Behavior" section of the README for the resulting contract.

---

## 1. Where we stand today

Exactly **one** response header is consumed anywhere in production code:
`Content-Encoding`, in `GzipClientHttpRequestInterceptor` (and only on the JDK
client path — Apache and Reactor Netty decompress internally). Every other
decision the library makes is driven by **status code and body alone**:

| Decision point | Input today |
|---|---|
| Retry (`SyncClientUtil.shouldRetryOn`, `WebClientUtil.shouldRetryOn`) | status code only, via `HttpClientUtil.isRetryableStatus` |
| Backoff (`executeWithRetry`, `Retry.backoff`) | `base * 2^attempt` + jitter; no server input |
| Error mapping (`OpenTmfResponseErrorHandler`, `OpenTmfRestClientStatusHandler`, reactive `errorWrappingFilter`) | status + body; **headers are in hand and discarded** |
| Error body parsing (`ErrorBodyExtractor`) | body only; JSON detected by trial-parse, charset by heuristic |
| Circuit-breaker failure classification (`ResilienceClientHttpRequestInterceptor`, `ReactiveResilience`) | status code only |
| Bearer token TTL (`TokenEntry.from`, `TokenCacheUtil`) | `expires_in` × `cache-safety-factor` |

A repo-wide grep finds zero occurrences of `Retry-After`, `Cache-Control`,
`ETag`, `Vary`, `WWW-Authenticate`, `RateLimit`, `X-Request-Id` or `Location`
in any source, YAML or doc file.

## 2. The layering principle (important, and it decides several cases)

This library is **the transport layer**. `opentmf-api-clients` sits on top of
it and owns **TMF protocol semantics**. That boundary already exists in code:

- `tmf630-toolkit` (server side) *writes* `X-Total-Count`, `X-Result-Count`,
  `Content-Range` and `Link`.
- `opentmf-api-clients` (client side) *reads* them —
  `ResponseHeaderUtil.getXTotalCount(...)` and
  `getContentRangeItemCount(...)`, working off `ResponseEntity`.

So **TMF-semantic headers are already solved one layer up and must not be
duplicated here.** The rule this document applies throughout:

> A header belongs in `opentmf-http-clients` only if it informs a decision the
> transport layer already owns — retrying, authenticating, breaking a circuit,
> decoding a body, or reporting an error. Everything that expresses *what the
> resource means* belongs to `opentmf-api-clients`.

There is one real gap in that split, and it motivates §3: `ResponseHeaderUtil`
works from a `ResponseEntity`, so the upper layer sees headers **on success
only**. On the error path this library throws
`OpenTmfClientResponseException`, which carries status and body but no
headers — so the upper layer is header-blind exactly when a diagnosis is most
needed.

## 3. The one enabling change everything else depends on

`OpenTmfClientResponseException` has two state fields, `statusCode` and
`responseBody`. All three error handlers hold the live response and drop its
headers. By the time `shouldRetryOn` runs, the response is closed and the
headers are unrecoverable.

**Nothing in tiers A or B below is possible without first capturing response
headers into that exception.** It is a small change with one constraint worth
flagging: the four existing constructors are load-bearing —
`HttpClientUtil` looks them up *reflectively by arity* for exception remapping
(`(HttpStatusCode)`, `(HttpStatusCode, String)`,
`(HttpStatusCode, String, String)`) — so a header-carrying variant must be
**additive**, and subclasses such as `OpenTmfClientNotFoundException` must keep
the existing arities intact.

Design notes for that capture:

- Store an **immutable, read-only** copy (`HttpHeaders.readOnlyHttpHeaders`).
  A live `ClientHttpResponse`'s headers must not escape the handler — the
  response is closed immediately after.
- The exception is `Serializable` (`serialVersionUID = 4L`). `HttpHeaders` is
  serializable, but this is worth a conscious decision rather than an
  accident — bumping the UID and testing round-trip.
- Consider a **redacting** accessor, or at minimum documenting that headers
  may contain sensitive values, since exceptions get logged wholesale by
  consumers. See §7.

---

## 4. Tier A — worth reading and acting on

### 4.1 `Retry-After` (RFC 9110 §10.2.3)

**Verdict: adopt. Highest value-to-risk ratio of anything in this document.**

**Benefit.** `429` and `503` are both already in `RETRYABLE_STATUS_CODES`, so
the library *does* retry them — on a blind `base * 2^attempt` schedule while
the server is explicitly telling us when to come back. Honouring it means
fewer wasted round-trips against rate-limited vendors, and it stops retry
from amplifying an outage the origin is already trying to shed. This is the
single clearest mismatch between what the library knows and what it uses.

**Harm if done naively — this is a genuine availability risk.** The value is
**vendor-controlled input that parks our threads**. A misconfigured (or
hostile, or merely confused) origin returning `Retry-After: 86400` would, on
the sync path, put a pooled thread into `Thread.sleep` for a day. With enough
concurrent callers that is a self-inflicted denial of service, and it is worse
than the current blind backoff. `Retry-After` must therefore never be honoured
unbounded.

**Extent.**

- Honour it on `429` and `503` only. (RFC also allows it on `3xx`; ignore
  that — we do not own redirect handling, see §6.4.)
- Support **both** grammars: `delay-seconds` and `HTTP-date`. The date form
  needs clock-skew tolerance — clamp a negative or absurd computed delay to
  zero rather than trusting it.
- Apply a **hard cap** (`max-retry-after`, suggested default 30s–60s) with
  explicit fail-fast beyond it: if the server asks for longer than we are
  willing to wait, do not sleep and do not retry — surface the error with the
  requested delay in the message. That converts a hang into a fast, diagnosable
  failure.
- Cap and jitter still apply; a shared `Retry-After` across many callers
  creates a thundering herd at expiry, so jitter must not be dropped.
- Reactive side needs a custom `Retry.from(companion -> ...)`;
  `Retry.backoff` cannot express a per-attempt delay.
- Adjacent fix to fold in: the sync backoff `baseMs * (1L << attempt)` is
  uncapped and the shift is undefined at `attempt >= 63`. Unreachable with
  realistic `num-retries`, but if we touch backoff at all, cap it here.

**Interaction with the circuit breaker (worth deciding deliberately).** A
`429` carrying `Retry-After` is a *scheduled, known* backoff, not a health
signal. Counting it toward the failure rate conflates "we are being throttled"
with "the origin is sick". `429` is not in the default `record-status-codes`
(`500/502/503/504`), so today's behaviour is already correct by default — but
the interaction should be documented so nobody adds `429` to that list without
understanding it.

### 4.2 `WWW-Authenticate` (RFC 9110 §11.6.1; bearer specifics RFC 6750 §3)

**Verdict: adopt, narrowly.**

**Benefit.** Bearer refresh today is purely *proactive*: TTL is
`expires_in × cache-safety-factor`, computed once at token issue. Nothing
reacts to a token that stops working early — revocation, a rotated signing
key, an issuer clock skew, or simply a `cache-safety-factor` set too close to
1.0. In all of those the caller just gets a `401`. Reading the challenge closes
the loop reactively.

The distinction that makes this safe is in the header, not the status:
RFC 6750 defines `error="invalid_token"` (→ *the credential is stale; refresh
once and retry*) versus `error="insufficient_scope"` or `invalid_request`
(→ *this is an authorization problem; refreshing will never help*). Without
reading it, a naive refresh-on-401 becomes an **infinite refresh loop against
a permissions misconfiguration**, hammering the IdP.

**Harm.** Two, both manageable: (a) the retry must be **once, non-recursive**,
guarded by a flag on the attempt, never a loop; (b) it must invalidate the
cached token *before* re-requesting, or it will re-present the same dead token.
There is also a stampede risk — N concurrent callers all getting `401` and all
refreshing simultaneously — which argues for single-flight refresh per client
id.

**Extent.** On `401` **with** a bearer challenge whose `error` is
`invalid_token` (or absent, which many vendors do): evict the cache entry,
fetch one new token, replay the request exactly once. Any other `error` value:
do not refresh, fail immediately with the challenge's `error_description` in
the message. Do not implement challenge *negotiation* (choosing among multiple
schemes) — we only ever speak bearer or basic, both configured.

Note this composes with the circuit breaker: the token call shares the owner's
breaker instance, so a dead IdP still opens the circuit as designed.

### 4.3 `Content-Type` (RFC 9110 §8.3)

**Verdict: adopt in `ErrorBodyExtractor`. Low risk, removes a guess.**

**Benefit.** `ErrorBodyExtractor` currently decides "is this JSON?" by
attempting `readTree` and catching the failure, and decodes text with a UTF-8
heuristic in `decodeAsText`. The header makes both deterministic. It also lets
us treat `application/problem+json` (RFC 9457) as a first-class shape distinct
from TMF's own error body — the probe list (`detail`, `error_description`,
`message`, …) is already a superset of Problem Details fields, but knowing
which contract we are parsing beats pattern-matching on field names. And the
`charset` parameter fixes genuinely non-UTF-8 vendors, which the heuristic
today can only mangle.

**Harm.** Minimal, with one caveat: vendors lie. A response labelled
`text/html` that contains JSON, or `application/json` on an HTML error page
from an intervening proxy, are both common in the wild. So `Content-Type` must
**inform** the parse, not gate it — keep the trial-parse as fallback rather
than replacing it. Removing the fallback would be a regression.

**Extent.** Use it to choose the charset and to pick the parse strategy first;
retain today's behaviour as fallback. No new configuration.

### 4.4 Correlation identifiers (`X-Request-Id`, `X-Correlation-Id`, `traceparent`)

**Verdict: adopt as diagnostics only. Cheapest meaningful win here.**

**Benefit.** Disproportionate operational value for near-zero complexity. The
README today only shows users setting these on *requests*. Capturing the
vendor's identifier off the *response* and putting it in the exception message
and the error log is what turns "the vendor returned 500" into a support ticket
the vendor can actually act on. In a dsync/dnms topology with several hops,
this is often the difference between a one-hour and a one-week diagnosis.

**Harms — two specific ones, both real.**

1. **Never put these in metric tags.** A request id is unbounded-cardinality
   by construction; as a Micrometer tag it would blow up the registry and the
   TSDB behind it. They belong in logs and exception messages only. (Contrast
   with the existing `client=<name>` pool gauges, which are bounded.) Worth
   stating in the code comment, because it is exactly the mistake a future
   contributor makes.
2. **Log injection.** These are attacker-influenceable strings being written
   into log lines. Strip CR/LF and cap the length before logging, or a hostile
   vendor can forge log entries.

**Extent.** A configurable list of header names to echo (defaulting to the
three above), copied into the exception message and MDC-style log context.
Never a metric tag, never a retry or resilience input. No behaviour change —
purely observability.

> **Note (2.1.7):** the *opposite* direction — proactively sending our own W3C
> `traceparent` on outbound **requests** — is implemented: all library-built
> clients are wired to the application's Micrometer `ObservationRegistry`, so
> consumers running Micrometer-tracing get outbound trace-context propagation
> automatically (see README, "Observability"). This section remains about
> *reading* the callee's identifiers off the *response* on failure, which is
> still a future candidate.

### 4.5 Rate-limit headers (`RateLimit-Remaining` / `-Reset` / `-Limit`, `X-RateLimit-*`)

**Verdict: adopt as *metrics only*, best-effort. Do not let it drive
behaviour.**

**Benefit.** The natural companion to the Apache pool gauges already shipped:
`opentmf.client.ratelimit.remaining{client=<name>}` next to
`opentmf.client.pool.*` gives operators advance warning *before* the `429`s
start, rather than a post-mortem after. Bounded cardinality (one series per
client id), so it fits the existing gauge model exactly.

**Harm.** The header family is **not settled**. The IETF work
(`draft-ietf-httpapi-ratelimit-headers`) is still stabilising, and real
vendors ship `X-RateLimit-*`, `RateLimit-*`, and bespoke spellings with
inconsistent semantics (remaining-in-window vs. remaining-in-quota; reset as
epoch seconds vs. delta seconds). Any parsing is therefore guesswork, and
**guesswork must never gate a request**. Proactive client-side throttling
based on a misread `Reset` would silently stall traffic for a reason no
operator could see — much worse than absorbing a `429` we already retry
correctly.

**Extent.** Parse best-effort, publish gauges, log at debug on parse failure,
and stop there. Header names configurable per client. Explicitly **not** an
input to retry, bulkhead sizing, or the circuit breaker.

---

## 5. Tier B — expose, but do not act on

These are genuinely useful, but the machinery to act on them either belongs to
the caller or is a feature in its own right. All of them fall out of the §3
header capture for free, which is the argument for doing §3 first.

**`ETag` / `Last-Modified` (RFC 9110 §8.8).** Two distinct uses. *Conditional
GET* (`If-None-Match` → `304`) is real value for the catalog-polling pattern
behind `HttpClientRegistry` — but it needs a response cache (see below).
*Optimistic concurrency* (`If-Match` on PATCH/PUT → `412`) is arguably the more
interesting one for TMF resource updates, and it needs **no cache at all** —
just the caller holding the `ETag` between a read and a write. That second use
is a strong argument for exposing the header even if we never cache anything.

**`Cache-Control` / `Expires` / `Age` / `Vary` (RFC 9111).** For ordinary GETs,
`max-age` is *more* valuable than `ETag` revalidation: a validator only saves
the payload, since you still pay the round-trip to learn it is a `304`; a
freshness lifetime skips the request entirely. `stale-if-error` (RFC 5861)
composes unusually well with the resilience work — serving stale data when the
breaker is open is the natural completion of the fail-fast story.

**But building an HTTP cache in this library is the wrong call, and `Vary` is
why.** These clients send `Authorization` headers. A cache that ignores
`Vary` in a multi-tenant deployment can serve one tenant's data to another.
That is not a performance bug, it is a data-leak, and it is the strongest
argument against a casually-added cache in a *shared* client library. Correct
caching also means honouring `no-store`, `private`, `must-revalidate`, and
`Age` from intermediaries; RFC 9111 is long for a reason. **Recommendation:
expose, do not build.** If it is ever wanted in-library, scope it explicitly —
GET only, opt-in per client id, cache key including the resolved
`Authorization`, `Vary` and `no-store` honoured, bounded Caffeine store — and
treat it as a feature, not a header read.

`Age` deserves a mention on its own: surfaced as a debug log or exception
field, it answers "why is this vendor returning data I know is stale" by
revealing an intermediary, without a packet capture.

**`Location` (RFC 9110 §10.2.2).** TMF's async patterns return `202 Accepted`
plus a monitor resource URL, and `201 Created` plus the new resource's URI.
Because the library deserializes bodies and discards headers, that URI is
currently unreachable to callers. Worth exposing; the *polling* of a monitor
is TMF semantics and belongs in `opentmf-api-clients` per §2.

**`Link` (RFC 8288).** `rel="next"`/`"prev"` pagination — `tmf630-toolkit`
already emits it. Squarely a §2 upper-layer concern; expose only.

**`Content-Disposition` (RFC 6266), `Content-Range`, `Accept-Ranges`.**
Relevant if TMF attachment/document endpoints are ever consumed through this
library. `Content-Range` is *already* parsed by
`opentmf-api-clients`' `ResponseHeaderUtil` for TMF's `items` unit — do not
duplicate it here.

**`Allow` (RFC 9110 §10.2.1).** On a `405`, it names the methods the resource
accepts. Nearly free to fold into the exception message and turns an opaque
`405` into a self-explaining one.

**`Deprecation` (RFC 9745) / `Sunset` (RFC 8594).** A single WARN log when
present. Thematically apt for a client aimed at versioned vendor APIs, costs
almost nothing, and gives ops lead time before an endpoint disappears. The
only harm is log noise on a per-response basis, so it should be logged once
per client id per interval, not per call.

---

## 6. Tier C — deliberately ignore; honouring them would be harmful

### 6.1 `Cache-Control: no-store` on the token endpoint

**This is the trap in this document.** RFC 6749 §5.1 *requires* OAuth2 token
endpoints to respond with `Cache-Control: no-store` and `Pragma: no-cache`.
So essentially every compliant auth server we talk to is already telling this
library "do not store this response" — and the library is **right to
disregard it**.

That directive exists to keep credentials out of *shared and on-disk* caches:
browsers, proxies, CDNs. An in-process, in-memory Caffeine cache in a
confidential client is the standard and intended pattern, which is exactly
what `TokenCacheUtil` builds. Honouring `no-store` literally would disable
token caching entirely and produce a token request per outbound call — a
self-inflicted storm against the IdP, and precisely the failure the
`cache-safety-factor` design exists to prevent.

**Action: none in behaviour; documentation only.** Today this is correct *by
accident* rather than by decision, which makes it exactly the kind of thing a
well-meaning contributor "fixes" later. It deserves a comment in
`TokenCacheUtil` and a line in the README stating that the header is
deliberately not honoured, and why.

### 6.2 `Set-Cookie`

**Verdict: ignore, and consider asserting it.** A server-to-server API client
should be stateless. Silently accumulating cookie state would make otherwise
identical requests behave differently depending on history, break the
assumption that any pooled connection is interchangeable, and — in a
multi-tenant client — risk carrying one tenant's session onto another's call.
Spring's clients do not maintain a cookie store by default, so the current
behaviour is correct; the point is to keep it that way deliberately. A debug
log when a vendor unexpectedly sets a session cookie would be a useful
diagnostic (it usually means we are talking to a UI-oriented endpoint rather
than an API).

### 6.3 Browser-only security headers

`Strict-Transport-Security`, `Content-Security-Policy`,
`X-Frame-Options`, `X-Content-Type-Options`, `Access-Control-Allow-*`. All are
directives to a *browser*. A server-to-server client acting on them is
meaningless at best. HSTS in particular must **not** be interpreted as
permission to rewrite scheme or pin transport — TLS policy here comes from
configuration (`ssl-protocol`, the configured truststore), which is
auditable, whereas a header is not. **Ignore entirely; not even logged.**

### 6.4 `Location` as automatic redirect following

Distinct from exposing `Location` (§5). Each transport already owns redirect
policy (`ApacheRestTemplateFactory`, `JdkRestTemplateFactory`,
`WebClientConfigUtil`), and it is configurable there. Re-implementing
redirect-following at the library layer would double-follow, bypass the
transports' loop protection, and — most seriously — risk **replaying the
`Authorization` header to a different host** on a cross-origin redirect, which
is a credential-leak primitive. Leave it entirely to the transport.

---

## 7. Cross-cutting harms to weigh against any of the above

These apply to header-driven behaviour in general and are the reason this
document recommends "expose" far more often than "act".

1. **Vendor-controlled input becomes our control flow.** Every header we act
   on is a lever a remote party can pull on our runtime. `Retry-After` parking
   threads (§4.1) is the sharpest example, but the principle generalises:
   anything read must be range-checked, capped, and safe when absent, garbage
   or hostile.
2. **Metric cardinality.** Any header value promoted to a Micrometer tag must
   have bounded cardinality. Request ids and `Retry-After` values do not.
3. **Log injection and secret leakage.** Header values written to logs need
   CR/LF stripping and length caps. And once §3 puts headers on the exception,
   any consumer logging the exception logs the headers — so `Authorization`
   echoes, `Set-Cookie`, and `WWW-Authenticate` parameters warrant redaction
   at the capture point, not at the log site.
4. **Configuration sprawl.** Each header read tends to arrive with a knob
   (enable/disable, name override, cap). The existing property surface is
   already large and now fully documented in config metadata; five more knobs
   per header is a real cost. Prefer sane fixed behaviour over configurability
   wherever the correct answer does not vary by deployment.
5. **Silent behaviour change on upgrade.** Anything that alters retry or
   caching changes the timing profile of existing consumers without their
   asking. Default-off, or default-on-but-strictly-safer, and a CHANGELOG entry
   that says so plainly.
6. **Layer duplication.** Per §2, re-reading in this library what
   `opentmf-api-clients` already reads produces two parsers that will drift.

---

## 8. Summary verdict table

| Header | Verdict | Extent |
|---|---|---|
| `Retry-After` | **Act** | 429/503; both grammars; hard cap + fail-fast; keep jitter |
| `WWW-Authenticate` | **Act** | 401 + `invalid_token` → single non-recursive refresh & replay |
| `Content-Type` | **Act** | charset + parse strategy in `ErrorBodyExtractor`; keep trial-parse fallback |
| `X-Request-Id` / `traceparent` | **Act** | logs & exception message only; never a metric tag |
| `RateLimit-*` | **Act (metrics only)** | best-effort gauges; never gates a request |
| `ETag` / `Last-Modified` | Expose | enables caller-side `If-Match` concurrency without any cache |
| `Cache-Control` / `Age` / `Vary` | Expose | do **not** build a cache; `Vary` + `Authorization` is a leak risk |
| `Location` | Expose | 201/202 monitor URL; polling belongs to api-clients |
| `Link` | Expose | pagination — upper layer already owns it |
| `Allow` | Expose | fold into the 405 exception message |
| `Deprecation` / `Sunset` | Expose | throttled WARN, once per client id |
| `Content-Range`, `X-Total-Count` | **Do not touch** | already handled in `opentmf-api-clients` |
| `Cache-Control` on token endpoint | **Deliberately ignore** | document the decision; do not "fix" |
| `Set-Cookie` | **Ignore** | keep the client stateless; debug log only |
| HSTS / CSP / CORS / `X-Frame-Options` | **Ignore** | browser-only; never influence TLS policy |
| `Location` as auto-redirect | **Ignore** | transport owns it; auth-replay risk |
| `Connection`, `Keep-Alive`, `Transfer-Encoding`, `Content-Length` | **Ignore** | transport-owned |

---

## 9. Suggested scope if we act in 2.1.6

Ordered by value-to-risk, each independently shippable:

1. **Capture headers into `OpenTmfClientResponseException`** (§3), additive
   constructor, read-only copy, redaction at capture. Unlocks everything else
   and immediately fixes the "upper layer is header-blind on errors" gap.
2. **Document the token-endpoint `no-store` decision** (§6.1). Pure
   documentation, prevents a future regression, costs nothing.
3. **`Retry-After` with a hard cap** (§4.1), plus the uncapped-shift fix in
   `executeWithRetry`.
4. **Correlation-id echo into errors and logs** (§4.4).
5. **`Content-Type` in `ErrorBodyExtractor`** (§4.3).
6. **`WWW-Authenticate`-driven single refresh-and-replay on 401** (§4.2) —
   most valuable of the behavioural changes but also the one touching the
   most delicate existing code, so it wants its own release.

Items 1, 2, 4 and 5 are behaviour-preserving or additive. Item 3 changes retry
timing and item 6 changes auth flow, so both need a CHANGELOG note under a new
`## [2.1.6]` section and, for item 3, a `max-retry-after` property with a
conservative default.

## 10. Open questions for discussion

1. **Header capture on the success path.** §3 fixes the error path. Do we also
   want headers reachable on success from *this* layer, or is
   `ResponseEntity` in `opentmf-api-clients` sufficient? (I lean: sufficient —
   do not widen this library's API.)
2. **`Retry-After` cap default.** 30s, 60s, or "never exceed
   `request-timeout`"? The last is self-consistent but may be surprisingly
   short.
3. **Does a capped `Retry-After` respect `num-retries`,** or does an
   explicit server instruction justify one extra attempt beyond it?
4. **Single-flight token refresh.** Worth adding alongside §4.2, or is the
   existing proactive TTL enough to make the stampede rare?
5. **Redaction policy.** Blocklist (`Authorization`, `Set-Cookie`,
   `Proxy-Authenticate`) or allowlist? Allowlist is safer but will hide
   vendor-specific diagnostic headers, which is half the point of capturing.
6. **Is any consumer actually blocked on `ETag`/`If-Match` concurrency
   today,** or is that speculative? It changes the priority of §5 materially.
