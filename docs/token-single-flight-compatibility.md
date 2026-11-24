# One mint per token key: what a client on defaults sees (measured 2026-11-15)

> **Record** of a comparison measured on 2026-11-15, on the unreleased line after 2.5.1 (then
> `2.5.2-SNAPSHOT`). That line was renumbered 2.6.0 on 2026-11-21, which is what this file calls
> it, and was never released: it became 3.0.0 (see the CHANGELOG). The behaviour measured here is
> unchanged in 3.0.0. The harness, `CompatTokenCellsIT`, names no API that 3.0.0 removed and compiles
> against it (checked at e7c6b01).

Row OTMF-HTTP-CLIENTS-TOKEN-FAILURE-NOT-REPEATED-BY-EVERY-CALLER-1 (ledger #4243). The change makes
concurrent callers of one token key share one mint, adds the opt-in `bearer-auth.failure-memory`
(unset by default) and jitters the token call's status retries. The compatibility gate: a client
that sets nothing new sees the same token calls, outcomes, exceptions and counter for a single
caller as before, and differs only where callers are concurrent. This file is that claim,
measured, not read from the code.

## How it was measured

- **The harness:** [`CompatTokenCellsIT`](token-single-flight-compatibility/CompatTokenCellsIT.java),
  copied into each checkout below and run on its own (`-Dit.test=CompatTokenCellsIT`). It
  measures and asserts nothing. Each cell is a context of the starter with one bearer client
  (`opentmf.http-clients.backend.*`), a `MeterRegistry`, and the registered `backendTokenService`
  asked for a token. It logs the token calls the endpoint received, each caller's outcome (the
  failure's type, status and cause chain), the counter `opentmf.client.token.fetch` and the
  time.
- **The token endpoint:** a plain socket. Every answer closes its connection, so one call is one
  connection.
  - *token*: `200` with `{"access_token":"tok","expires_in":300}`;
  - *503*: `503` without `Retry-After`;
  - *close*: the connection closed without an answer (a transport failure);
  - *401*: `401` with `{"error":"invalid_client"}`;
  - *empty*: `200` with an empty body.

  With 8 callers, each answer is held back 300 ms, so every caller asks while the first mint is
  in flight.
- **The client:** defaults (`num-retries: 3`), `retry-wait-duration: 200ms`, no
  `bearer-auth.failure-memory`; `jdk` and `apache` through the `SyncTokenService`, `netty`
  through the reactive `TokenService`.
- **Rounds:** a cell of 2 rounds asks twice, one after the other: the second *token* round is
  served from the cache, the second *503* round finds nothing remembered.
- **Three checkouts, one machine, one after the other:**
  - **2.5.1:** the release tag `opentmf-http-clients-2.5.1` (79eaa06);
  - **develop:** ccbae16, the head this change branches from;
  - **branch:** `feature/token-single-flight`, this change.

## Result

**A single caller: 21 of 21 cells identical between develop and the branch** in token calls,
outcome, exception chain and counter. The only change is the time of the *503* cells: their three
retry waits are now jittered (±50 % of each wait), so a 1.4 s chain took 1.3 to 1.6 s.

**Concurrent callers: the 6 cells with 8 callers differ as intended.** One mint instead of eight:
- *token*: 8 calls become 1;
- *503*: 32 calls become 4;
- every caller still gets the same outcome as before;
- the counter counts the one mint (`ok=1` / `failed=1` instead of 8).

**Against 2.5.1** every cell matches develop except the type of a failed token call's status
error. 2.5.1 raised an `OpenTmfClientResponseException`; develop and the branch raise a
`BearerTokenException`, its subclass. That is 2.6.0's earlier "A failed token call is its own
type" change, not this one.

| Type | Endpoint | Rounds × callers | 2.5.1 calls | develop calls | branch calls | Outcome on the branch (per caller) | Counter on the branch | 2.5.1 ms | develop ms | branch ms |
|---|---|---|---|---|---|---|---|---|---|---|
| jdk | token | 1 × 1 | 1 | 1 | 1 | token | ok=1 | 237 | 231 | 173 |
| jdk | token | 2 × 1 | 1 | 1 | 1 | token, token (cached) | ok=1 | 16 | 20 | 14 |
| jdk | 503 | 1 × 1 | 4 | 4 | 4 | BearerTokenException(503) | failed=1 | 1434 | 1435 | 1602 |
| jdk | 503 | 2 × 1 | 8 | 8 | 8 | BearerTokenException(503) twice | failed=2 | 2850 | 2845 | 3027 |
| jdk | close | 1 × 1 | 2 | 2 | 2 | BearerTokenTransportException ← ResourceAccessException ← IOException ← EOFException | failed=1 | 15 | 13 | 13 |
| jdk | 401 | 1 × 1 | 1 | 1 | 1 | BearerTokenException(401) | failed=1 | 11 | 11 | 8 |
| jdk | empty | 1 × 1 | 1 | 1 | 1 | BearerTokenResponseException(200) | failed=1 | 24 | 11 | 11 |
| jdk | token | 1 × 8 | 8 | 8 | **1** | token ×8 | **ok=1** (was 8) | 329 | 314 | 308 |
| jdk | 503 | 1 × 8 | 32 | 32 | **4** | BearerTokenException(503) ×8 | **failed=1** (was 8) | 2629 | 2635 | 2817 |
| apache | token | 1 × 1 | 1 | 1 | 1 | token | ok=1 | 56 | 50 | 69 |
| apache | token | 2 × 1 | 1 | 1 | 1 | token, token (cached) | ok=1 | 6 | 5 | 8 |
| apache | 503 | 1 × 1 | 4 | 4 | 4 | BearerTokenException(503) | failed=1 | 1417 | 1415 | 1531 |
| apache | 503 | 2 × 1 | 8 | 8 | 8 | BearerTokenException(503) twice | failed=2 | 2829 | 2827 | 2380 |
| apache | close | 1 × 1 | 2 | 2 | 2 | BearerTokenTransportException ← ResourceAccessException ← NoHttpResponseException | failed=1 | 6 | 5 | 6 |
| apache | 401 | 1 × 1 | 1 | 1 | 1 | BearerTokenException(401) | failed=1 | 4 | 5 | 4 |
| apache | empty | 1 × 1 | 1 | 1 | 1 | BearerTokenResponseException(200) | failed=1 | 3 | 3 | 4 |
| apache | token | 1 × 8 | 8 | 8 | **1** | token ×8 | **ok=1** (was 8) | 308 | 307 | 305 |
| apache | 503 | 1 × 8 | 32 | 32 | **4** | BearerTokenException(503) ×8 | **failed=1** (was 8) | 2616 | 2618 | 2807 |
| netty | token | 1 × 1 | 1 | 1 | 1 | token | ok=1 | 397 | 391 | 570 |
| netty | token | 2 × 1 | 1 | 1 | 1 | token, token (cached) | ok=1 | 14 | 13 | 32 |
| netty | 503 | 1 × 1 | 4 | 4 | 4 | BearerTokenException(503) | failed=1 | 1456 | 1448 | 1302 |
| netty | 503 | 2 × 1 | 8 | 8 | 8 | BearerTokenException(503) twice | failed=2 | 2860 | 2860 | 2705 |
| netty | close | 1 × 1 | 2 | 2 | 2 | BearerTokenTransportException ← WebClientRequestException ← PrematureCloseException | failed=1 | 21 | 16 | 30 |
| netty | 401 | 1 × 1 | 1 | 1 | 1 | BearerTokenException(401) | failed=1 | 9 | 9 | 21 |
| netty | empty | 1 × 1 | 1 | 1 | 1 | BearerTokenResponseException(200) | failed=1 | 9 | 7 | 15 |
| netty | token | 1 × 8 | 8 | 8 | **1** | token ×8 | **ok=1** (was 8) | 314 | 314 | 315 |
| netty | 503 | 1 × 8 | 32 | 32 | **4** | BearerTokenException(503) ×8 | **failed=1** (was 8) | 2640 | 2638 | 2437 |

On 2.5.1 every `BearerTokenException(503)` and `BearerTokenException(401)` above was an
`OpenTmfClientResponseException` with the same status: the earlier change named above.

## What the cells do not show, and where it is pinned

- **The trace of a waiting caller.** The mint runs on the thread of the caller that started it
  (sync), or subscribed with that caller's Reactor context (reactive). A waiting caller's trace
  has no token call in it, only the wait. Pinned by `BearerTokenServiceSingleFlightTest` (the
  mint sees the first caller's context, not the second's) and `TokenMintsTest` (the sync mint
  runs on the caller's own thread).
- **A reactive caller that cancels** stops waiting. The mint is not cancelled with it; it
  finishes, and its token is cached for the next caller (`BearerTokenServiceSingleFlightTest`).
- **The failure each waiting caller gets** is its own copy, of the same type and with the same
  message, status, headers, `Retry-After` and cause (`TokenMintsTest`).
- **The opt-in `bearer-auth.failure-memory`** is not set in any cell above. Its own cells are in
  `TokenMintsIT` (the second wave of an outage makes no call; a `401` is not remembered;
  `clearCache()` forgets) and in `TokenMintsTest` (the memory's end; a `Retry-After` lengthens it
  up to `max-retry-after`).
