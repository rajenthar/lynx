# ADR-004: End-to-End Idempotency via Idempotency-Key

**Status:** Accepted

**Date:** 2026-07-04

**Deciders:** Rajenthar

---

## Context

A critical requirement for financial systems:

**Same request sent twice = same result, no duplicate charge**

Example failure scenario:

```
User initiates transfer:
  POST /v1/transfers
  Body: { from_account: 'ACC-123', to_account: 'ACC-456', amount: 100 SGD }
  
Response: 500 Internal Server Error (network timeout)
  Transfer may or may not have been created
  
User doesn't know if it worked, retries:
  POST /v1/transfers (same request)
  
Result: If not idempotent:
  ├─ First request: 100 SGD deducted
  ├─ Second request: 100 SGD deducted again (duplicate!)
  └─ User's account: -200 SGD instead of -100 SGD ❌
```

**Without idempotency: Users lose money on retries.**

---

## Decision

**Implement end-to-end idempotency via Idempotency-Key header.**

```
Client sends:
  POST /v1/transfers
  Headers: { Idempotency-Key: "unique-uuid-for-this-transfer" }
  Body: { from_account: 'ACC-123', to_account: 'ACC-456', amount: 100 SGD }

Server processes:
  1. Check: Has this Idempotency-Key been seen before?
  2. If yes: Return cached response (no re-processing)
  3. If no: Process transfer, cache response, return

Retry with same Idempotency-Key:
  POST /v1/transfers
  Headers: { Idempotency-Key: "unique-uuid-for-this-transfer" }
  
Response: Same as first request (idempotent)
  ✓ No duplicate charge
  ✓ Same saga_id, same result
```

---

## Rationale

### 1. Database-Level Idempotency (Not Just Cache)

**Weak approach (cache-based):**
```
API Gateway cache:
  If Idempotency-Key in cache:
    Return cached response
  Else:
    Process request, cache response

Problem: Cache expires or is lost
  ├─ Client retries after cache expiry
  ├─ Server re-processes (duplicate charge)
  └─ Still not safe ❌
```

**Strong approach (database constraint):**
```
ledger table:
  UNIQUE(saga_id, idempotency_key, entry_type)
  -- entry_type included because one saga legitimately writes multiple
  -- legs (HOLD_DR + HOLD_CR) sharing the same saga_id + idempotency_key
  
Insert attempt 1:
  INSERT ledger (saga_id, idempotency_key, entry_type, amount, ...)
  VALUES ('saga-uuid-1', 'idempotency-key-xyz', 'HOLD_DR', 100, ...)
  → Success ✓

Insert attempt 2 (retry, same keys):
  INSERT ledger (saga_id, idempotency_key, entry_type, amount, ...)
  VALUES ('saga-uuid-1', 'idempotency-key-xyz', 'HOLD_DR', 100, ...)
  → UNIQUE constraint violation ❌ (rejected by database)
  
Result: Duplicate prevented at database level ✓

NOTE: The retry carries the SAME saga_id only because saga_id is
derived deterministically from (user_id + idempotency_key) — see
Section 2. With a randomly generated saga_id this constraint would
NOT catch retries.
```

**Why UNIQUE constraint is better:**
- ✓ Survives process crashes
- ✓ Survives cache expiry
- ✓ Enforced by database (not app logic)
- ✓ Works across all services

### 2. Deterministic Saga-ID Derivation (Critical Design Decision)

**saga_id is NOT random. It is derived deterministically from (user_id + idempotency_key).**

**The problem with random saga_id:**

```
Retry with same idempotency_key:

First attempt:
  Generate random saga_id = uuid-1
  INSERT ledger (saga_id=uuid-1, idempotency_key=key-A, ...)

Retry (same idempotency_key):
  Generate random saga_id = uuid-2  ← DIFFERENT!
  INSERT ledger (saga_id=uuid-2, idempotency_key=key-A, ...)
  → UNIQUE(saga_id, idempotency_key) checks combination (uuid-2, key-A)
  → Combination not in database → INSERT SUCCEEDS
  → DUPLICATE SAGA CREATED ❌ (system broken — user charged twice)
```

**The fix — derive saga_id deterministically:**

```java
// Same inputs ALWAYS produce same saga_id
private String generateSagaId(String userId, String idempotencyKey) {
  String combined = userId + "|" + idempotencyKey;
  return UUID.nameUUIDFromBytes(combined.getBytes()).toString();  // deterministic UUID
}
```

**Why include user_id, not just idempotency_key?**

```
Two DIFFERENT clients may accidentally send the same UUID:

Client A (user-123): Idempotency-Key = "key-X"
Client B (user-456): Idempotency-Key = "key-X"  ← same UUID by accident

If saga_id = f(idempotency_key) only:
  Both derive same saga_id → Client B blocked by Client A's transfer ❌

With saga_id = f(user_id + idempotency_key):
  User-123 → saga_id = hash("123|key-X") = "saga-abc"
  User-456 → saga_id = hash("456|key-X") = "saga-def"
  → Different saga_ids, both transfers proceed independently ✓
```

**Where user_id comes from:** the JWT `sub` claim, validated by api-gateway. Lynx's auth-service issues user IDs as UUIDs (no enumeration attacks, distributed-system friendly). The derivation works with any string identifier though — external IdP subjects like `auth0|abc123` would hash just as well.

**Now retries are safe:**

```
First attempt:
  User-123, key-A → saga_id = hash("123|key-A") = "saga-xyz"
  INSERT (saga_id="saga-xyz", idempotency_key="key-A") → Success ✓

Retry (same user, same key):
  User-123, key-A → saga_id = hash("123|key-A") = "saga-xyz"  ← SAME (deterministic)
  INSERT → UNIQUE constraint violation → duplicate prevented ✓
```

### 3. UNIQUE Constraint Strategy

**Why UNIQUE(saga_id, idempotency_key) as a composite, not UNIQUE(saga_id) alone?**

Since saga_id is deterministic, UNIQUE(saga_id) alone would technically catch retries. We keep the composite constraint as **defense-in-depth against hash collisions**:

```
Hash collision scenario (extremely rare, but possible):
  User-123, key-A → saga_id = "saga-xyz"
  User-456, key-B → saga_id = "saga-xyz"  ← accidental collision!

With UNIQUE(saga_id) alone:
  Second insert rejected → innocent user blocked ❌
  (looks like a duplicate but is a different request)

With UNIQUE(saga_id, idempotency_key):
  ("saga-xyz", "key-A") vs ("saga-xyz", "key-B") → different combinations
  → Both allowed; collision is detectable in logs instead of silently blocking ✓
```

Collision probability is astronomically low (~1 in 2^122) — acceptable. The composite constraint costs nothing extra and documents intent explicitly.

**Ledger schema with idempotency protection:**

```sql
CREATE TABLE ledger (
  id BIGSERIAL PRIMARY KEY,
  saga_id UUID NOT NULL,          -- deterministic: hash(user_id + idempotency_key)
  user_id UUID NOT NULL,          -- from JWT sub claim (audit + saga_id derivation input)
  idempotency_key UUID NOT NULL,  -- client-provided
  entry_type VARCHAR(50) NOT NULL,
  amount DECIMAL(18,2),
  account_id UUID,
  created_at TIMESTAMP DEFAULT NOW(),
  
  -- entry_type included: one saga legitimately writes multiple legs
  -- (HOLD_DR + HOLD_CR share the same saga_id + idempotency_key)
  UNIQUE(saga_id, idempotency_key, entry_type)
);
```

**Flow:**

```
Saga step 1 (HOLD) — first attempt:
  INSERT ledger (saga_id, idempotency_key, entry_type='HOLD_DR', ...)  ✓
  INSERT ledger (saga_id, idempotency_key, entry_type='HOLD_CR', ...)  ✓
  → Different entry_types, both legs allowed ✓

Retry (same request re-executes the same transaction):
  INSERT ledger (saga_id, idempotency_key, entry_type='HOLD_DR', ...)
  → UNIQUE constraint violation on first leg
  → Entire transaction rolled back (atomicity)
  → Duplicate prevented ✓
```

### 4. Client Generates Idempotency-Key

Client (not server) generates the key:

```
Client code:
  idempotencyKey = UUID.randomUUID()  // generate once
  
  // First attempt
  POST /v1/transfers
    Headers: { Idempotency-Key: idempotencyKey }
  
  // If timeout/error, retry
  POST /v1/transfers
    Headers: { Idempotency-Key: idempotencyKey }  // same key!
```

**Why client-generated:**
- ✓ Client can retry with same key (idempotent)
- ✓ Server doesn't need to generate/allocate
- ✓ Client owns the "request identity"

**Client must store key:**
```
Client-side:
  POST /v1/transfers
    Headers: { Idempotency-Key: "uuid-123" }
    
  Save to localStorage:
    { idempotencyKey: "uuid-123", status: "PENDING" }
  
  If network timeout:
    Retry with same idempotencyKey from localStorage
    
  On success:
    Clear from localStorage
```

### 5. 24-Hour TTL (Housekeeping)

Idempotency keys are unique per saga, but old ones can be cleaned up:

```sql
-- Delete old idempotency keys (older than 24 hours)
DELETE FROM ledger 
WHERE created_at < NOW() - INTERVAL '24 hours'
  AND idempotency_key IS NOT NULL;
```

**Why 24 hours:**
- ✓ Covers client retries during a business day
- ✓ Prevents ledger bloat (old entries deleted)
- ✓ After 24h, transfer is definitely settled (not stuck)

**Doesn't affect safety:**
- UNIQUE constraint still active within 24h window
- Duplicates still prevented ✓

### 6. Three IDs, Three Different Jobs

**Layer 1: Idempotency-Key (this ADR)**
- Client-facing, request-level
- Prevents duplicate requests from same client
- 24-hour TTL

**Layer 2: Saga-ID (from ADR-001)**
- Internal, saga-level
- Derived deterministically from (user_id + idempotency_key) — see Section 2
- Tracks multi-step orchestration
- Prevents orchestrator from re-processing same saga

**Layer 3: Correlation-ID (distributed tracing — NOT idempotency)**
- For tracing a request's path across services (observability/debugging)
- Does NOT prevent duplicates — it is never checked for uniqueness
- Multiple related requests may intentionally share one Correlation-ID (e.g., a batch)
- All services log it, so one grep reconstructs the full request path

```
Do not confuse the three:
  Idempotency-Key → prevents duplicate REQUESTS   (client-facing, enforced)
  Saga-ID         → tracks saga PROGRESS           (internal, derived)
  Correlation-ID  → traces requests ACROSS SERVICES (observability, never enforced)
```

**Combined effect:**

```
Request 1: Idempotency-Key-A
  → Saga-ID-1 created
  → Ledger entries with idempotency_key = A
  → Response cached

Retry with Idempotency-Key-A:
  → Database rejects duplicate idempotency_key ✗
  → Return cached response ✓

Different request: Idempotency-Key-B
  → Saga-ID-2 created
  → New ledger entries
  → No conflict with Saga-ID-1 ✓
```

---

## Implementation Details

### Request Processing

**Core principle: cache is for SPEED, database is for CORRECTNESS.**

The system must remain fully correct even if the cache is wiped entirely. The DB UNIQUE constraint is the safety net that is never bypassed; the cache only absorbs retry traffic so most duplicates never touch Postgres.

```java
@PostMapping("/v1/transfers")
public TransferResponse createTransfer(
    @RequestHeader("Idempotency-Key") String idempotencyKey,
    @RequestBody TransferRequest req,
    HttpServletRequest httpReq
) {
  String userId = getAuthenticatedUserId(httpReq);      // from JWT sub claim
  String cacheKey = userId + "|" + idempotencyKey;      // scoped per user
  String sagaId = generateSagaId(userId, idempotencyKey); // deterministic

  // Step 1: Cache check (FAST PATH — performance optimization only)
  // Most retries are served here in ~1ms without touching Postgres
  TransferResponse cached = idempotencyCache.get(cacheKey);
  if (cached != null) {
    return cached;
  }

  try {
    // Step 2: Just try to create — the DB is the judge.
    // If this is a duplicate, the UNIQUE constraint rejects it.
    TransferResponse result = transferService.createTransfer(
      req, sagaId, userId, idempotencyKey
    );

    // Step 3: Cache on success (for future retries)
    idempotencyCache.put(cacheKey, result);
    return result;

  } catch (UniqueConstraintViolation e) {
    // Duplicate confirmed by the database.
    //
    // IMPORTANT: Do NOT read from cache here — we already checked it in
    // Step 1 and missed. (Classic case: first attempt inserted the entry,
    // then crashed BEFORE cache.put — so the cache never had it.)
    //
    // The database entry from the first attempt is the source of truth.
    LedgerEntry entry = ledgerService.findBySagaId(sagaId);
    if (entry == null) {
      // Constraint fired but entry not readable — should never happen
      throw new IllegalStateException("Duplicate detected but original entry not found");
    }

    // Reconstruct the first attempt's response from the DB entry
    TransferResponse result = constructResponseFromEntry(entry);

    // Backfill the cache so the NEXT retry takes the fast path
    idempotencyCache.put(cacheKey, result);
    return result;
  }
}
```

**Why the catch block reads from DB, not cache:**

```
Crash scenario (why cache can't be trusted in the catch block):

Request 1:
  T1: cache.get() → miss
  T2: INSERT to DB → success ✓
  T3: SERVER CRASHES before cache.put() ← response never cached!

Request 2 (retry):
  T4: cache.get() → miss (never cached)
  T5: INSERT to DB → UNIQUE violation
  T6: cache.get() again? → STILL a miss ❌ would return null
  T6: DB read instead → finds first attempt's entry → correct response ✓
```

**The three layers working together:**

| Layer | Role | If it fails |
|---|---|---|
| Cache (Redis) | Fast path — absorbs retry traffic | Falls through to DB — still safe |
| DB UNIQUE constraint | Correctness guarantee | Never bypassed — always enforced |
| DB read on violation | Recovers original response | Source of truth |

### Ledger Insert with Idempotency-Key

```sql
BEGIN TRANSACTION (SERIALIZABLE);
  -- saga_id is deterministic: hash(user_id + idempotency_key)
  INSERT INTO ledger (saga_id, user_id, idempotency_key, entry_type, amount, account_id)
  VALUES (
    'saga-uuid-derived',            -- same on every retry (deterministic)
    'user-123',                     -- from JWT sub claim
    'idempotency-key-abc-def-123',  -- client-provided
    'HOLD_DR',
    100.00,
    'user-account-123'
  );
  
  -- Retry re-derives the SAME saga_id, so:
  -- → UNIQUE(saga_id, idempotency_key, entry_type) violation
  -- → Entire transaction rolled back (outbox insert included)
  -- → Duplicate prevented ✓

  INSERT INTO outbox (saga_id, event_type, payload)
  VALUES ('saga-uuid-derived', 'TransferHeld', '{...}');
COMMIT;
```

### Idempotency Key Format

```
HTTP Header: Idempotency-Key
Format: UUID v4 (canonical format)
Example: 550e8400-e29b-41d4-a716-446655440000

Client-side generation:
  JavaScript: crypto.randomUUID()
  Python: uuid.uuid4()
  Java: UUID.randomUUID()
  Go: uuid.New()
```

### Response Caching

```
Distributed cache (Redis — shared across all API instances):

Cache key: user_id + "|" + Idempotency-Key   ← scoped per user!
Cache value: { statusCode, responseBody, timestamp }
TTL: 24 hours

Example:
  Key: "user-123|550e8400-e29b-41d4-a716-446655440000"
  Value: {
    statusCode: 200,
    responseBody: { saga_id: 'saga-xyz', status: 'HOLDING' },
    timestamp: 2026-07-04T10:30:00Z
  }

Why user_id in the cache key:
  Two users accidentally sending the same Idempotency-Key UUID
  must NOT hit each other's cached responses.
  Same reasoning as including user_id in saga_id derivation.
```

---

## Consequences

### Positive

- ✅ **No duplicate charges**: UNIQUE constraint prevents duplicates at database level
- ✅ **Safe retries**: Client can retry with same Idempotency-Key without risk
- ✅ **Survives crashes**: Database constraint works even if service restarts
- ✅ **Simple for clients**: Just send a UUID, done
- ✅ **Financial safety**: Prevents one of the most critical errors in payments

### Negative

- ❌ **Cache overhead**: In-memory cache stores responses (memory cost)
- ❌ **Cleanup job**: Must delete old idempotency keys after 24h (operational task)
- ❌ **Client responsibility**: Clients must generate and resend Idempotency-Key

### Mitigations

| Consequence | Mitigation |
|---|---|
| Cache memory cost | Use distributed cache (Redis). 24h TTL prevents unlimited growth. |
| Cleanup job | Scheduled DELETE job: `DELETE FROM ledger WHERE created_at < NOW() - '24 hours'`. Run nightly. |
| Client implementation | Provide SDK/library that handles Idempotency-Key generation and caching. |

---

## Alternatives Considered

### Alternative 1: Retry-After Header (Weak)

Server returns `Retry-After` header, client retries later:

```
Response: 503 Service Unavailable
Headers: { Retry-After: 60 }

Client: Waits 60 seconds, retries
```

**Why rejected:**
- ❌ Doesn't prevent duplicates (just delays retry)
- ❌ If server processing was already started, retry causes duplicate
- ❌ No safety guarantee

### Alternative 2: Database Transaction Rollback (Incomplete)

If request fails, rollback entire saga:

```
Saga begins
  HOLD written ✓
  FX lock fails ✗
  Rollback: Delete HOLD entry
  
Retry:
  HOLD written again ✓
  ...
```

**Why rejected:**
- ❌ Incomplete (doesn't prevent duplicates, just undoes failures)
- ❌ Complex (need compensating transactions for every step)
- ❌ Not idempotent (different saga_ids on retry)

### Alternative 3: Request Deduplication Service (Overkill)

Separate "dedup service" checks for duplicate requests:

```
API → Dedup Service → Check: Is this request duplicate?
                      ↓
                      If yes: Return cached response
                      If no: Forward to ledger-service
```

**Why rejected:**
- ❌ Additional service (operational complexity)
- ❌ Not simpler than UNIQUE constraint
- ❌ Still needs database-level enforcement

### Alternative 4: Optimistic Locking (Weaker)

Use version numbers to detect duplicates:

```
Idempotency check:
  SELECT ledger WHERE saga_id = ? AND idempotency_key = ?
  If exists: return cached
  If not: insert
  
Problem: Race condition between SELECT and INSERT
         Two requests both see "not exists"
         Both insert → duplicate ❌
```

**Why rejected:**
- ❌ Race condition (not atomic)
- ❌ UNIQUE constraint is simpler and safer

---

## Related ADRs

- [ADR-001: Ledger-first Saga](ADR-001-ledger-first-saga.md) — Saga-ID for orchestration
- [ADR-002: Transactional Outbox](ADR-002-transactional-outbox.md) — Atomic outbox inserts with ledger
- [ADR-003: Saga Orchestrator](ADR-003-saga-orchestrator.md) — Recovery worker (uses idempotency)

---

## Security Considerations

**Idempotency-Key is NOT a secret:**
```
✓ Should be sent in HTTPS (encrypted in transit)
✓ No sensitive data in the key
✓ UUID is not predictable (cryptographically random)
✗ Not equivalent to API key or token
```

**Rate limiting (OPEN — not yet designed or built, tracked so it isn't forgotten):**
```
Rate limit by API key/userId, not Idempotency-Key
  ✓ Prevents brute-force attacks
  ✓ Limits per-user, not per-request-id
```
Two genuinely different "rate limiting" concerns exist in this system, easy to
conflate — worth keeping distinct:

1. **JWKS refetch rate-limiting — already handled, nothing to build.**
   `JwtVerifier.fromJwksUrl(...)` (`lynx-security`) uses Nimbus's
   `JWKSourceBuilder`, which already rate-limits its own refetch of
   auth-service's public keys (time-based refresh, plus refetch-on-unknown-
   `kid` for key rotation, rate-limited so a flood of bogus `kid`s can't
   hammer the JWKS endpoint). This is internal library behavior, not
   something this project configures.
2. **API-level rate limiting (per-user/per-IP request throttling) — NOT
   built anywhere yet.** No service in this codebase currently limits how
   often a caller can hit an endpoint. The natural home for this is
   `api-gateway` (the edge, in front of every other service) — but
   `api-gateway` is still an empty placeholder module (Phase 1, not yet
   started). Until it exists, every service (including `ledger-service`)
   is fully open to being hammered by a misbehaving or malicious caller,
   same root cause as [other-docs/08 Decision 23]'s missing balance
   validation — protections this codebase hasn't gotten to yet, not
   protections it decided against.

**Status: documented, deliberately deferred until `api-gateway` is built.**
Design not yet started — token-bucket vs. sliding-window, per-user vs.
per-IP vs. both, where the limit state lives (in-memory per instance vs.
shared Redis, the same in-memory-vs.-shared tradeoff ADR-007's
`ServiceTokenProvider` cache already worked through) are all still open.

**Audit trail:**
```
Log every request with Idempotency-Key
  ├─ First request: log full details
  ├─ Retry (same key): log as "cache hit"
  └─ Compliance: Can trace every transfer attempt
```

---

## Open Requirement: `saga-orchestrator`'s recovery MUST reuse the original `Idempotency-Key`, never mint a fresh one (not yet built, tracked here)

Everything above proves retries are safe **when the retry presents the same
`Idempotency-Key` it used the first time** — a live client resubmitting its
own in-flight request naturally does this, since it's the same request
object. `saga-orchestrator`'s own recovery/redo loop (ADR-003) is a
**different case**: it re-drives a stuck phase (e.g. `lock` never got a
response) as a **brand-new HTTP call**, constructed from scratch, possibly
long after the original attempt. If that redo generates a **fresh**
`Idempotency-Key` instead of reusing the original one, both protections in
this ADR are bypassed entirely:

- The Redis cache lookup (keyed on `userId|key|phase`) misses — different key.
- The DB's `UNIQUE(saga_id, idempotency_key, entry_type)` constraint doesn't
  fire either — the tuple is genuinely different (new `idempotency_key`).

Nothing stops the redo from writing a **second, genuinely duplicate** set of
`LOCK_DR`/`LOCK_CR` legs — silently, with no error, no constraint violation,
no cache hit. This is a correctness requirement on the CALLER, not something
`ledger-service` can enforce on its own — `ledger-service`'s only contract is
"same key → same result"; it has no way to know a given key is a fresh mint
versus a legitimate reuse.

**Required design (not yet built, since `saga-orchestrator` doesn't exist
yet):** `saga-orchestrator` must persist the `Idempotency-Key` it generates
for each phase call — e.g. one column per phase on `saga_state`
(`hold_idempotency_key`, `lock_idempotency_key`, etc.) — the first time it
calls that phase, and reuse that **exact same** key on every subsequent
redo of that phase. A redo is a retry of the same logical attempt, not a new
one, and must be treated like one.

**Status: documented, not yet implemented** — `saga-orchestrator` itself
doesn't exist yet; tracked here so this requirement isn't lost by the time
it's built. See also [DECISIONS.md](DECISIONS.md)'s Known Open Gaps table.

### Related, already-resolved question: does reusing `hold`'s key for `lock` corrupt the DB?

No — asked and worth stating explicitly. `UNIQUE(saga_id, idempotency_key,
entry_type)` includes `entry_type`, and `HOLD_DR`/`HOLD_CR` are different
`entry_type` values from `LOCK_DR`/`LOCK_CR`. So a caller mistakenly reusing
`hold`'s key for the `lock` call would NOT violate the DB constraint at
all — `lock` would insert its own new rows just fine. The bug this project
actually found and fixed (java-docs/08, the `SagaPhase` discriminator) was
purely at the **Redis cache layer**: before the fix, the cache key didn't
include the phase, so `lock` could return `hold`'s cached *response object*
without ever reaching the database — a completely different failure mode
from a DB constraint violation, and the reason the cache key is
`userId|key|phase`, not just `userId|key`.

---

## References

- Idempotent API Design: https://stripe.com/blog/idempotency
- UNIQUE Constraints: https://www.postgresql.org/docs/current/sql-createtable.html
- UUID Standard: https://www.rfc-editor.org/rfc/rfc4122.html
- Distributed Cache (Redis): https://redis.io/
