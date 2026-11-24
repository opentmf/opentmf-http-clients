# `exchange-timeout`: what a client that does not set it sees (measured 2026-11-15)

> **Record** of a comparison measured on 2026-11-15, on the unreleased line after 2.5.1 (then
> `2.5.2-SNAPSHOT`). That line was renumbered 2.6.0 on 2026-11-21, which is what this file calls
> it, and was never released: it became 3.0.0 (see the CHANGELOG). The behaviour measured here is
> unchanged in 3.0.0. The harness, `CompatCellsIT`, names no API that 3.0.0 removed and compiles
> against it (checked at e7c6b01).

Row OTMF-HTTP-RESPONSE-TIMEOUT-MEANS-THREE-THINGS-1, part 2 (ledger #4190). `exchange-timeout` is
opt-in and unset by default; the gate for it is that a client that does not set it sees the same
timings and the same exceptions as before, on all three client types. This file is that claim,
measured, not read from the code.

## How it was measured

- **The harness:** [`CompatCellsIT`](exchange-timeout-compatibility/CompatCellsIT.java), copied
  into each checkout below and run on its own (`-Dit.test=CompatCellsIT`). It measures and asserts
  nothing: each cell logs the outcome, the time and the exception chain (types only; the
  messages carry a random port).
- **The peer:** a plain socket answering `200` with `Content-Length`, so a byte can be held back:
  - *complete*: headers and the 8-byte body at once;
  - *500*: a `500` with an 8-byte body at once;
  - *stall*: headers after 1.2 s with 8 of 64 body bytes, then silence;
  - *trickle*: headers at once, then one of 8 body bytes every 700 ms (the last at 4.9 s);
  - *late headers*: headers and body after 4 s.
- **The client:** `response-timeout: 2000ms`, `num-retries: 0`, no `exchange-timeout`; Logbook at
  `TRACE` (it reads the body inside the exchange) or not on the classpath.
- **Three checkouts, one machine, one after the other:**
  - **2.5.1:** the release tag `opentmf-http-clients-2.5.1` (79eaa06);
  - **develop:** d50e416, the head this change branches from;
  - **branch:** f19c5d6, with `exchange-timeout`.

## Result

**Develop against the branch: 24 of 24 cells identical** in outcome and exception chain, the
times within 17 ms of each other. **2.5.1 against the branch: 23 of 24 identical.** The one
difference (apache, stall, Logbook writing) is not this change: develop has it too. It is the fix
of 2.6.0's "Fixed" entry, where on 2.5.1 a body cut short came back as a `200` without a body when
Logbook logged it.

| Type | Peer | Logbook | 2.5.1 ms | develop ms | branch ms | Outcome on the branch |
|---|---|---|---|---|---|---|
| jdk | complete | off | 156 | 208 | 210 | completed, body abcdefgh |
| jdk | complete | on | 88 | 123 | 140 | completed, body abcdefgh |
| jdk | status 500 | off | 26 | 27 | 41 | failed: OpenTmfClientResponseException |
| jdk | stall | off | 2004 | 2003 | 2003 | failed: RestClientException ← IOException ← IOException ← IOException |
| jdk | stall | on | 2025 | 2020 | 2012 | failed: RestClientException ← IOException ← IOException ← IOException |
| jdk | trickle | off | 2002 | 2003 | 2002 | failed: RestClientException ← IOException ← IOException ← IOException |
| jdk | trickle | on | 2012 | 2014 | 2013 | failed: RestClientException ← IOException ← IOException ← IOException |
| jdk | late headers | off | 2003 | 2003 | 2003 | failed: ResourceAccessException ← HttpTimeoutException |
| apache | complete | off | 66 | 52 | 46 | completed, body abcdefgh |
| apache | complete | on | 13 | 11 | 12 | completed, body abcdefgh |
| apache | status 500 | off | 7 | 4 | 3 | failed: OpenTmfClientResponseException |
| apache | stall | off | 3205 | 3206 | 3205 | failed: RestClientException ← SocketTimeoutException |
| apache | stall | on | 3214 | 3213 | 3209 | failed: RestClientException ← SocketTimeoutException — **2.5.1: completed, body null** |
| apache | trickle | off | 4903 | 4903 | 4902 | completed, body abcdefgh |
| apache | trickle | on | 4912 | 4918 | 4909 | completed, body abcdefgh |
| apache | late headers | off | 2003 | 2004 | 2004 | failed: ResourceAccessException ← SocketTimeoutException |
| netty | complete | off | 439 | 388 | 402 | completed, body abcdefgh |
| netty | complete | on | 30 | 20 | 22 | completed, body abcdefgh |
| netty | status 500 | off | 20 | 21 | 21 | failed: OpenTmfClientResponseException |
| netty | stall | off | 3221 | 3221 | 3221 | failed: WebClientResponseException ← ReadTimeoutException |
| netty | stall | on | 3215 | 3217 | 3216 | failed: WebClientResponseException ← ReadTimeoutException |
| netty | trickle | off | 4908 | 4921 | 4909 | completed, body abcdefgh |
| netty | trickle | on | 4914 | 4921 | 4913 | completed, body abcdefgh |
| netty | late headers | off | 2008 | 2013 | 2016 | failed: WebClientRequestException ← ReadTimeoutException |

The first call of a type in a fresh context (*complete*, Logbook off) includes the client's
start-up and varies by tens to hundreds of milliseconds between runs on every checkout alike.
