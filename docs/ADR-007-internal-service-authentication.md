# ADR-007: Internal Service-to-Service Authentication for Saga Phases

**Status:** Accepted — Option C (service identity + on-behalf-of `userId`)
is the settled direction. Its client-side mechanics
(`ServiceTokenProvider`/`CircuitBreaker` in `lynx-security`) AND
`ledger-service`'s own server-side acceptance of both auth shapes
(`internal-service`-role token + `onBehalfOfUserId` field, enforced in
`LedgerController#userId`) are designed, implemented, and tested — see
"Implementation Details" below. What's left is `auth-service` actually
issuing these tokens (it doesn't exist yet) and `saga-orchestrator` being
the real caller — tracked explicitly, not blocking this ADR's acceptance.

**Date:** 2026-08-15

**Deciders:** Rajenthar

---

## Context

Every Lynx service currently authenticates callers the same way:
`JwtAuthFilter` verifies a live, user-signed Bearer JWT and extracts
`userId` from its `sub` claim (ADR-004's defense-in-depth model — every
service independently verifies, none trust a caller-supplied identity).
This was built and tested against exactly one shape of caller: a real
end-user, holding a live token, making a synchronous HTTP request.

That assumption breaks once `saga-orchestrator` (ADR-003) exists and
actually drives a saga:

```
User calls ONCE:
  POST /v1/transfers  (Authorization: Bearer <user's live JWT>)
  → saga-orchestrator creates the saga, returns immediately

Some time later — seconds, minutes, or longer with retries/backoff —
saga-orchestrator itself calls, as its OWN internal process, with no
live end-user request behind it anymore:
  POST ledger-service /hold
  POST ledger-service /lock
  POST ledger-service /settle   (or /release)
```

**The user's original JWT is not available for phases 2–4.** It was
presented once, for the original request, and that request is long
over. `ledger-service`'s `JwtAuthFilter` has no mechanism today for a
caller that isn't a live, freshly-authenticated end-user — it will
simply reject every one of `saga-orchestrator`'s internal phase calls
with 401.

Three options were considered, worked through by direct discussion
before any implementation:

### Option A: `saga-orchestrator` stores and replays the user's original JWT

Rejected. JWTs are short-lived (minutes); a saga can easily outlive that
window, especially with retries/backoff — `settle` could 401 on a
perfectly legitimate, still-in-progress saga purely because the token
expired mid-flight. It also means persisting a live user credential
inside `saga-orchestrator`'s own saga-state store for a potentially
long-running process — a real credential-handling smell; bearer tokens
shouldn't sit at rest in a workflow database.

### Option B: `saga-orchestrator` uses its own service identity; `ledger-service` derives `userId` from that token

Rejected as-is. `userId` is load-bearing today — it's part of saga_id
derivation (ADR-004) and will presumably matter for audit ("who actually
authorized this transfer"). If the caller's JWT belongs to
`saga-orchestrator`, `userId` would silently become the orchestrator's
own identity, not the real customer's — breaking that semantic
everywhere it's used.

### Option C: separate "who is calling me" from "who is this on behalf of" — chosen direction, not yet designed in detail

The caller authenticates as **itself** — `saga-orchestrator` presents its
own service-issued token, verified independently by `ledger-service`
with the same rigor `JwtVerifier` already applies to end-user tokens
today (same mechanism, plausibly a different issuer/audience or a role
claim like `internal-service`). The **actual end-user's `userId`**
travels as an explicit field in the request body/DTO — trusted **only
because the calling service has already been proven to be an
authenticated, authorized internal caller**, not because it's
self-asserted by an untrusted public client. This is the same shape as
OAuth2's token-exchange / "actor claim" pattern (RFC 8693): the token
proves which service is acting, a separate claim/field carries who it's
acting for.

---

## Decision

**Adopt Option C** as the direction — service-to-service calls
authenticate with a service identity, and the real end-user's identity
travels as an explicit, service-asserted field, trusted because the
calling service itself was verified.

**This ADR is intentionally incomplete.** It exists to record the
decision that was made (which of the three options) and rule out the
other two with their reasoning, WITHOUT yet designing the concrete
mechanics — those depend on decisions not yet made in `auth-service`
(which doesn't exist yet) and `saga-orchestrator` (same). Concretely
still open — one bullet below is now resolved for `ledger-service`
specifically (see "Implementation Details"), the rest remain open:

- How does `auth-service` issue a distinct "service identity" token to
  `saga-orchestrator` (and any other future internal caller)? A separate
  issuer/audience? A role/claim on the same token shape? A completely
  different token type (mTLS client cert instead of a JWT)? **Still open**
  — `auth-service` doesn't exist yet.
- ~~What does `ledger-service`'s `JwtAuthFilter` need to look like to
  accept BOTH shapes?~~ **Resolved** — a role claim (`internal-service`)
  on the SAME `JwtVerifier`/`UserContext` shape already in use; no new
  filter needed. See "Implementation Details."
- Does every phase endpoint (`hold`/`lock`/`settle`/`release`) actually
  need to accept both shapes, or are they saga-orchestrator-only in
  practice? **Resolved as: accept both**, decided pragmatically rather
  than closed off — `LedgerController#userId` honors a live end-user
  token exactly as before when no `internal-service` role is present, so
  nothing is lost if a phase endpoint is ever called directly by a user
  in some future flow; the on-behalf-of path is simply unused in that case.
- ~~Where does the trust boundary get enforced?~~ **Resolved** — enforced
  in `LedgerController#userId` itself: only a token carrying the
  `internal-service` role may supply `onBehalfOfUserId`; a normal
  end-user token attempting to supply one is rejected with 403. See
  "Implementation Details."

---

## Implementation Details: the service-token client (decided)

One piece of Option C **is** now fully decided, worked through by direct
Q&A, even though the surrounding pieces (above) aren't: **how a service
obtains, caches, and resiliently reuses its own service-identity token**
when calling another Lynx service (`saga-orchestrator` → `ledger-service`
is the first case; `ledger-service` → `account-service`, per
other-docs/08 Decision 23's balance-check discussion, will be a second).

### Where it lives: `lynx-security`, not per-service

Same reasoning as `JwtVerifier` being shared: this is pure mechanism, not
a per-service policy choice. Every future caller needs the identical
behavior — obtain a Client Credentials token, cache it, refresh it
safely, protect the refresh call with a circuit breaker. Nothing about
that varies between services, so it belongs in `lynx-security` as a new
`ServiceTokenProvider` class, exposing one method — `currentToken()` —
that internally handles everything below and just returns a valid token
string.

(Contrast with `JwtAuthFilter`, deliberately kept per-service — see the
`jwt-auth-filter-placement` memory — because IT bakes in real per-service
choices like which paths to skip. `ServiceTokenProvider` has no
equivalent per-service variation, so it doesn't get the same treatment.)

### Token lifetime: 1h issued, 50min reuse window

A 10-minute buffer before the token's real expiry — long enough to
absorb realistic clock skew between services, short enough that a token
is never held past most of its useful life.

### Concurrency: single-flight refresh, not naive re-fetch-per-caller

Without care, N concurrent callers hitting an expired cache at the same
instant would fire N simultaneous requests to `auth-service`'s token
endpoint — a self-inflicted thundering herd at exactly the moment being
avoided. `ServiceTokenProvider.currentToken()` refreshes under a lock:
the first caller to notice expiry performs the actual HTTP call; every
other concurrent caller blocks briefly and then reuses that same result,
rather than each starting its own fetch.

### Reactive invalidation, on top of TTL-based expiry

If a downstream service ever rejects a request with 401 despite the
local cache saying "still valid" (an edge case the 10-minute buffer
didn't catch, or the token was revoked early), the caller invokes
`ServiceTokenProvider.invalidate()`, forcing the next `currentToken()`
call to fetch fresh — then retries the original request **once**. Not an
unbounded retry loop; one retry, then a genuine failure.

### Circuit breaker: scoped to the token endpoint only, standard 3-state

The circuit breaker wraps ONLY the call from `ServiceTokenProvider` to
`auth-service`'s token endpoint — it has no visibility into, and no
effect on, calls the service makes to whatever it's actually trying to
reach (e.g. `ledger-service`'s `/hold`). If a caller still has a valid
cached token, it's completely unaffected by `auth-service` being down —
no call to `auth-service` happens at all during that window.

Standard three-state behavior, not a single-failure trip: **Closed**
(normal) → **Open** (after N consecutive failures, fail fast, stop
calling `auth-service`) → **Half-Open** (after a cooldown, let one trial
request through) → back to Closed on success or Open again on failure.

**A genuinely free synergy specific to this architecture:** while the
circuit is open and a caller can't get a fresh token, that one phase call
just fails — and because this is a saga, `saga-orchestrator`'s own
existing recovery/polling loop (ADR-003) already retries stuck phases
later, by which time `auth-service` has likely recovered. The circuit
breaker doesn't need to solve "what happens to the caller while open"
itself; the saga's own retry mechanism absorbs it, for free, because of
how this system is already built.

### Cache storage: in-process memory per instance, NOT shared Redis

Reconsidered from an earlier instinct to share it via Redis (the same
infra `IdempotencyCache` uses) — and deliberately walked back. Sharing
would genuinely help `IdempotencyCache` because it's checked on EVERY
business request, high volume. This token refreshes roughly once per
~50 minutes, PER INSTANCE — a trivial request rate (3 orchestrator
instances ≈ 3 requests to `auth-service` per ~50 minutes). Sharing via
Redis would add real complexity (cache key design, invalidation, another
dependency in the path) for a benefit that's unmeasurable at this call
frequency. Default to the simplest thing that works: a plain in-memory
holder, one per instance. Revisit only if this specific call volume is
ever actually observed to matter.

### Server-side acceptance (decided AND implemented, in `ledger-service`)

Unlike the rest of this ADR, this piece is not speculative: `ledger-service`
now genuinely accepts both auth shapes, enforced entirely in
`LedgerController#userId` — no new filter, no change to `JwtAuthFilter` or
`JwtVerifier`:

```java
private static String userId(HttpServletRequest request, String onBehalfOfUserId) {
  UserContext userContext =
      (UserContext) request.getAttribute(JwtAuthFilter.USER_CONTEXT_ATTRIBUTE);
  boolean isServiceCaller = userContext.hasRole(INTERNAL_SERVICE_ROLE);
  if (isServiceCaller) {
    if (onBehalfOfUserId == null || onBehalfOfUserId.isBlank()) {
      throw AuthException.unauthorized("Internal-service callers must supply onBehalfOfUserId");
    }
    return onBehalfOfUserId;
  }
  if (onBehalfOfUserId != null) {
    throw AuthException.forbidden("Only an internal-service caller may assert onBehalfOfUserId");
  }
  return userContext.userId();
}
```

The insight that made this simple: `JwtVerifier` already extracts a
`roles` claim into `UserContext` (built for a completely different
purpose — end-user authorization checks via `requireRole`). A
service-identity token is just a token with `internal-service` in that
same claim; `JwtAuthFilter` needed **zero changes** to support it — it
already verifies signature/issuer/audience/expiry identically regardless
of whose identity the token represents. Only the controller-level
decision of "whose `userId` do I use" needed new logic.

`onBehalfOfUserId` was added as a field on all four phase request DTOs
(`HoldRequest`/`LockRequest`/`SettleRequest`/`ReleaseRequest`) —
`String`, nullable, ignored entirely unless the caller is proven to be an
internal service first. Three behaviors, all covered by
`LedgerControllerIntegrationTest`:

| Caller | `onBehalfOfUserId` | Result |
|---|---|---|
| `internal-service` role | present | honored — becomes the `userId` for saga_id derivation |
| `internal-service` role | absent | 401 — a service caller MUST say who it's acting for |
| ordinary end-user token | present | 403 — only a proven service caller may assert this |
| ordinary end-user token | absent | unchanged — `sub` claim used, exactly as before |

This resolves 2 of the 4 previously-open questions above for
`ledger-service` specifically; `auth-service` issuing the
`internal-service`-role token in the first place is still open (tests
mint one directly, same precedent as `JwtVerifierTest`'s in-memory JWKS).

### Explicitly out of scope for this section (still open, see above)

How `auth-service` actually issues these tokens, how `ledger-service`
accepts both an end-user JWT shape and a service-token shape, and how the
"only `saga-orchestrator` may assert an on-behalf-of `userId`" trust
boundary gets enforced are all still undecided — this section only
resolves the CLIENT-SIDE mechanics of holding and reusing a token once
one exists, not the server-side issuance/acceptance design.

---

## Consequences

### Positive

- Keeps `userId`'s meaning consistent everywhere it's used (the real
  customer, never an internal service's own identity) — ADR-004's
  saga_id derivation and any future audit trail stay correct.
- No live user credential is ever persisted or replayed by
  `saga-orchestrator` — it only ever holds/presents its own
  service-issued token.
- Matches an established, well-understood pattern (OAuth2 token
  exchange / actor claims) rather than inventing something bespoke.

### Negative / Open Risk

- `ledger-service` (and every future service called by
  `saga-orchestrator`) needs to trust an on-behalf-of field asserted by
  an internal caller — a real widening of the trust boundary compared to
  today's "only ever trust what's cryptographically proven about the
  human" model. Needs careful scoping (see open questions above) so it
  can't be abused by a different, less-trusted internal service.
- Two different auth shapes (`Authorization: Bearer <user JWT>` vs.
  `Authorization: Bearer <service token> + on-behalf-of field`) now need
  to coexist in the same filter/service, adding real implementation
  complexity `JwtAuthFilter` doesn't have today.

---

## Related ADRs

- [ADR-003: Saga Orchestrator](ADR-003-saga-orchestrator.md) — the
  component that will actually make these internal calls
- [ADR-004: Idempotency](ADR-004-idempotency.md) — `userId`'s role in
  saga_id derivation, which this ADR must not break

## Related (non-ADR) docs

- `other-docs/03-lynx-security-design-decisions.md` — `lynx-security`'s
  current `JwtVerifier`/`JwtAuthFilter` design (end-user-only today)
- `docs/html/ledger-service-flows.html` (`#gaps` section) /
  `other-docs/08-ledger-service-design-decisions.md` (Decision 23) — the
  related, separately-discovered gap that `ledger-service` also has no
  balance-check mechanism yet; both gaps surface from the same root
  cause (today's design was built and tested against a single
  synchronous end-user request, not yet against an orchestrator-driven
  saga spanning multiple calls over time)
