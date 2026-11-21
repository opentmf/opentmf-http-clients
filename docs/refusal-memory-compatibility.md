# The refusal memory: what a client that sets nothing sees (measured 2026-11-20)

Row OTMF-HTTP-CLIENTS-FAILURE-MEMORY-ALSO-REMEMBERS-A-REFUSED-CREDENTIAL-1 (ledger #4587). The
change adds the opt-in `bearer-auth.refusal-memory`, unset by default. The compatibility gate: a
client that does not set it sees the same token calls, outcomes, exceptions and counter as
before. This file is that claim, measured, not read from the code.

## How it was measured

- **The harness:** the unchanged
  [`CompatTokenCellsIT`](token-single-flight-compatibility/CompatTokenCellsIT.java) of
  `docs/token-single-flight-compatibility.md`, with the same 27 cells (`jdk`, `apache`, `netty`;
  the endpoint answering a token, `503`, a closed connection, `401` and an empty body; 1 and 8
  callers; 1 and 2 rounds). It sets neither `failure-memory` nor `refusal-memory`.
- **Three checkouts, one machine, one after the other, 2026-11-20 11:59–12:03Z:**
  - **2.5.1:** the release tag `opentmf-http-clients-2.5.1` (79eaa06);
  - **develop:** 4cd04a0, the head this change branches from;
  - **branch:** `feature/refusal-memory`, this change.
- **Compared:** each cell's token calls, each caller's outcome (the failure's type and status),
  and the counter `opentmf.client.token.fetch`. The time is not compared: the machine was under
  another load (1-minute load 10 to 17).

## Result

**develop against the branch: 27 of 27 cells identical** in calls, outcomes and counter.

**2.5.1 against the branch:** 15 cells differ, all in earlier 2.6.0 changes, none in this one:
- 9 cells (`503` and `401` on each type): 2.5.1 raised `OpenTmfClientResponseException`, and the
  branch raises `BearerTokenException`, its subclass. This is "A failed token call is its own
  type".
- 6 cells (8 callers, token and `503`, on each type): 8 and 32 calls became 1 and 4, and the
  counter counts one mint. This is "one mint per token key".

The 12 other cells are identical. The `401` cells, the ones this change is about, make the same
single call on 2.5.1, on develop and on the branch.

## Where the setting itself is pinned

- `TokenMintsIT`, against a plain-socket token endpoint, sync and reactive:
  - **the measurement's shape:** 10 waves of 32 callers at a refused credential make 10 token
    calls without `refusal-memory` and 1 call with `10s`;
  - **two clients:** a refusal of one token URL is never served to the other client, which still
    calls and gets its token;
  - once the memory has passed, the fixed credential is used;
  - `refusal-memory` alone does not remember a `503`.
- `TokenMintsTest`:
  - the memory ends at exactly `refusal-memory`, and a `Retry-After` does not lengthen it;
  - `408`, `429`, 5xx, a transport failure and an unusable answer are not refusals;
  - a refusal of key A is not served to key B;
  - each memory keeps its own length when both are set;
  - `forget()` (`clearCache()`) drops a refusal.
- `ClientPropertiesValidatorTest`: a zero or negative `refusal-memory` is flagged.
