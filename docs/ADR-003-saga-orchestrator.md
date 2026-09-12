# ADR-003: Explicit Saga Orchestrator Over Choreography

**Status:** Accepted

**Date:** 2026-07-04

**Deciders:** Rajenthar

---

## Context

After deciding on ledger-first saga (ADR-001) and transactional outbox (ADR-002), the question becomes:

**Who controls the saga steps? Who decides the flow?**

Two approaches:

### Option A: Choreography (Services Decide)

```
ledger-service publishes: TransferHeld
  ↓
fx-service subscribes, checks: "Is this for me?"
  ↓
fx-service executes FX lock, publishes: RateLocked
  ↓
account-service subscribes, processes, publishes: TransferSettled
```

Services react to events. Decentralized control.

### Option B: Orchestration (Central Orchestrator Decides)

```
saga-orchestrator {
  1. Command: Write HOLD to ledger
  2. Wait for response
  3. Command: Lock FX rate
  4. Wait for response
  5. Command: Settle transfer
  6. Wait for response
}
```

Single service controls flow. Centralized control.

---

## Decision

**Implement explicit Saga Orchestrator pattern with centralized control.**

```
┌──────────────────────────────────────┐
│     Saga-Orchestrator Service        │
├──────────────────────────────────────┤
│ while (true) {                       │
│   // Poll for pending sagas          │
│   sagas = SELECT FROM saga_state     │
│            WHERE status = 'HOLDING'  │
│   for (saga in sagas) {              │
│     // Explicit step control         │
│     if (saga.status == 'HOLDING') {  │
│       lockFxRate(saga)               │
│       saga.status = 'FX_LOCKED'      │
│     }                                │
│     if (saga.status == 'FX_LOCKED') {│
│       settleFunds(saga)              │
│       saga.status = 'SETTLED'        │
│     }                                │
│   }                                  │
│   Thread.sleep(100)  // polling      │
│ }                                    │
└──────────────────────────────────────┘
         ↓ sends commands
┌────────────────────────────────────────────┐
│  ledger-service, fx-service, etc.          │
│  (execute commands, return responses)      │
└────────────────────────────────────────────┘
```

---

## Rationale

### 1. Explicit Control Flow

**Choreography (implicit):**
```
Event flow is implicit in code:
  fx-service listens for TransferHeld
  account-service listens for RateLocked
  
Question: What's the sequence?
Answer: You must read all 3 service codebases to understand
  ❌ Hard to debug
  ❌ Hard to change order
  ❌ Hidden dependencies
```

**Orchestration (explicit):**
```
Flow is explicit in saga-orchestrator code:
  STEP 1: holdFunds() ← visible in code
  STEP 2: lockFxRate() ← visible in code
  STEP 3: settleFunds() ← visible in code
  
Question: What's the sequence?
Answer: Read saga-orchestrator, clear and obvious
  ✓ Easy to debug
  ✓ Easy to change order
  ✓ Single source of truth for saga logic
```

### 2. Visibility into Saga State

**Choreography (distributed state):**
```
Where is the saga now?
  ├─ Query ledger: status = HOLD ✓
  ├─ Query fx-service: rate_locked = true?
  ├─ Query account-service: balance updated?
  └─ Stitch together answers
  
Result: Unclear, scattered state ❌
```

**Orchestration (centralized state):**
```
Where is the saga now?
  SELECT * FROM saga_state WHERE saga_id = 'uuid-1'
  Result: status = 'FX_LOCKED'
  
Answer: Clear, single query ✓
```

**Real-time visibility:**
```
Orchestrator dashboard:
  SELECT saga_state.status, COUNT(*) FROM saga_state GROUP BY status
  
  HOLDING: 523 sagas
  FX_LOCKED: 128 sagas
  SETTLED: 9,452 sagas
  FAILED: 3 sagas
  
One query, entire pipeline visibility ✓
```

### 3. Deterministic Recovery

**Choreography (recovery is implicit):**
```
Transfer stuck in limbo:
  ├─ Ledger has HOLD entry
  ├─ fx-service may or may not have locked rate
  ├─ account-service state unknown
  
Recovery: How do we know what to retry?
  ├─ Who failed? (unclear)
  ├─ What state is saga in? (must query 3 services)
  ├─ What should happen next? (implicit in 3 services' logic)
  └─ Risk: Retry the wrong step ❌
```

**Orchestration (recovery is explicit):**
```
Transfer stuck:
  saga_state shows: status = 'HOLDING'
  updated_at = 15 minutes ago
  
Recovery: Explicit and clear
  ├─ Saga is stuck in HOLDING phase
  ├─ Next step: lockFxRate()
  ├─ Retry: Call lockFxRate(saga) explicitly
  └─ Result: Deterministic recovery ✓
```

**Recovery worker:**
```java
while (true) {
  // Find stuck sagas
  List<Saga> stuck = query(
    "SELECT * FROM saga_state " +
    "WHERE updated_at < NOW() - INTERVAL '5 minutes'"
  );
  
  for (Saga saga : stuck) {
    // Explicit action based on status
    if (saga.status == "HOLDING") {
      lockFxRate(saga);  // Resume from here
    } else if (saga.status == "FX_LOCKED") {
      settleFunds(saga);  // Resume from here
    }
  }
  Thread.sleep(30000);  // Poll every 30 seconds
}
```

### 4. Easy to Implement Compensating Transactions

**Choreography (compensation is implicit):**
```
Transfer fails at SETTLE phase:
  HOLD applied ✓
  FX locked ✓
  SETTLE fails ❌
  
Compensation (rollback):
  Who decides to rollback?
  Who executes the rollback?
  
  Option 1: Each service listens for TransferFailed event
            Problem: Multiple services must understand compensation logic
            Risk: Inconsistent rollback ❌
```

**Orchestration (compensation is explicit):**
```
Transfer fails at SETTLE phase:
  Orchestrator detects failure
  Explicit compensation logic:
    1. Release HOLD (via ledger)
    2. Unlock FX rate (via fx-service)
    3. Notify user of failure
    4. Log audit trail
  
Compensation is centralized, testable, clear ✓
```

**Code example:**
```java
try {
  holdFunds(saga);
  saga.status = "HOLDING";
  
  lockFxRate(saga);
  saga.status = "FX_LOCKED";
  
  settleFunds(saga);
  saga.status = "SETTLED";
  
} catch (Exception e) {
  // Explicit compensation
  logger.error("Saga failed, compensating", e);
  
  if (saga.status == "FX_LOCKED") {
    unlockFxRate(saga);  // Release FX lock
  }
  if (saga.status == "HOLDING") {
    releaseHold(saga);   // Release held funds
  }
  
  saga.status = "FAILED";
  outbox.publish(new TransferFailed(saga.id, e.getMessage()));
}
```

### 5. Scalable and Stateless

**Orchestration with multiple instances:**

```
3 Saga-Orchestrators (load-balanced):

Orchestrator-1:
  Processes sagas with saga_id in range [0-333k]
  Or: processes sagas where saga_id % 3 == 0

Orchestrator-2:
  Processes sagas with saga_id in range [333k-666k]
  Or: processes sagas where saga_id % 3 == 1

Orchestrator-3:
  Processes sagas with saga_id in range [666k-1M]
  Or: processes sagas where saga_id % 3 == 2

Database handles concurrency via pessimistic locks (ADR-001)
  ├─ Only one orchestrator processes a given saga_id
  ├─ No conflicts
  └─ Linear scaling: 3 orchestrators = 3x throughput
```

**No special handling needed:**
```
All orchestrators run identical code:
  SELECT * FROM saga_state WHERE status = 'HOLDING' LIMIT 100
  
Postgres pessimistic lock (FOR UPDATE) ensures:
  ├─ Only one orchestrator locks each saga_id
  ├─ Others wait
  └─ Auto-distributes load
```

### 6. Easy to Test

**Choreography (implicit):**
```
Test: "What happens when fx-service fails?"
Problem: Must mock 3 services
  ├─ fx-service behavior
  ├─ account-service behavior
  ├─ ledger-service behavior
  
Test flow: implicit in event listeners
  Risk: Miss edge cases ❌
```

**Orchestration (explicit):**
```
Test: "What happens when lockFxRate fails?"

@Test
void testCompensationOnLockFxRateFail() {
  Saga saga = new Saga(uuid);
  saga.status = "HOLDING";
  
  when(fxService.lockRate(saga)).thenThrow(new TimeoutException());
  
  orchestrator.process(saga);
  
  assertEquals("FAILED", saga.status);
  verify(ledger).releaseHold(saga);  // Compensation executed
  verify(outbox).publish(TransferFailed);
}
```

All logic in one place, easy to test ✓
```

---

## Implementation Details

### Saga State Machine

```
          START
            │
            ↓
    ┌───────────────┐
    │   HOLDING     │
    └───────┬───────┘
            │
        (command: holdFunds)
            │
            ↓
    ┌───────────────┐
    │   FX_LOCKED   │
    └───────┬───────┘
            │
        (command: lockFxRate)
            │
            ↓
    ┌───────────────┐
    │   SETTLED     │
    └───────┬───────┘
            │
        (command: settleFunds)
            │
            ↓
         SUCCESS
         
OR at any step:
         FAILED ← (compensation)
```

### Polling vs Event-Driven

**Current design (polling with LIMIT + FOR UPDATE locks):**
```java
while (true) {
  // Pessimistic lock: only one orchestrator gets these rows
  holdingSagas = SELECT FROM saga_state 
                 WHERE status = 'HOLDING' 
                 LIMIT 100
                 FOR UPDATE;  // ← pessimistic lock acquired
  
  for (saga : holdingSagas) {
    lockFxRate(saga)
    saga.status = 'FX_LOCKED'
  }
  
  lockedSagas = SELECT FROM saga_state 
                WHERE status = 'FX_LOCKED' 
                LIMIT 100
                FOR UPDATE;  // ← pessimistic lock acquired
  
  for (saga : lockedSagas) {
    settleFunds(saga)
    saga.status = 'SETTLED'
  }
  
  Thread.sleep(100);  // 100ms polling interval
}
```

**How FOR UPDATE prevents duplicates:**

```
Without FOR UPDATE (broken):
  Orchestrator-1: SELECT LIMIT 100 → gets rows 1-100
  Orchestrator-2: SELECT LIMIT 100 → gets rows 1-100 (same!) ❌
  Orchestrator-3: SELECT LIMIT 100 → gets rows 1-100 (same!) ❌

With FOR UPDATE (correct):
  Orchestrator-1: SELECT LIMIT 100 FOR UPDATE → locks rows 1-100 ✓
  Orchestrator-2: SELECT LIMIT 100 FOR UPDATE → WAITS (locked)
  Orchestrator-3: SELECT LIMIT 100 FOR UPDATE → WAITS (locked)
  
  Orchestrator-1 commits:
  Orchestrator-2: Acquires lock on rows 101-200 ✓
  Orchestrator-3: WAITS
  
  Orchestrator-2 commits:
  Orchestrator-3: Acquires lock on rows 201-300 ✓
```

**With LIMIT batching:**
```
Iteration 1 (T=0ms):     Orchestrator-1 locks & processes sagas 1-100
Iteration 2 (T=100ms):   Orchestrator-2 locks & processes sagas 101-200
Iteration 3 (T=200ms):   Orchestrator-3 locks & processes sagas 201-300
Iteration 4 (T=200ms):   Orchestrator-1 locks & processes sagas 301-400
...

Result: Continuous pipelining (always processing sagas)
        No duplicates (FOR UPDATE ensures one orchestrator per batch)
        Fair distribution (queue of waiting orchestrators)
```

**With multiple orchestrators (3 instances, concurrent):**
```
All 3 run in parallel (different locked batches):
  T=0ms:
    Orchestrator-1: Locks & processes sagas 1-100
    Orchestrator-2: Waiting for Orchestrator-1 to commit
    Orchestrator-3: Waiting for Orchestrator-1 to commit
  
  T=100ms:
    Orchestrator-1: Locks & processes sagas 301-400 (if any)
    Orchestrator-2: Locks & processes sagas 101-200
    Orchestrator-3: Waiting for Orchestrator-2 to commit
  
  T=200ms:
    Orchestrator-1: Locks & processes sagas 501-600
    Orchestrator-2: Locks & processes sagas 401-500
    Orchestrator-3: Locks & processes sagas 201-300

Throughput = 100 sagas/100ms = 1000 sagas/sec (with 3 orchestrators)
             Each processes 33 sagas/sec, combined = 100 sagas/sec per phase
```

Pros:
- ✓ Simple (no message queue)
- ✓ Stateless orchestrator (survives crashes)
- ✓ Natural exponential backoff (retry on failure)
- ✓ No extra dependencies (no Kafka, no Temporal)

Cons:
- ❌ 100ms latency (vs event-driven ~1ms)

Trade-off: Acceptable for 600ms transfer latency.

**Why NOT Event-Driven for Saga State:**

Could we use Debezium CDC on saga_state changes and trigger orchestration via Kafka events?

```
Idea: saga_state changes → outbox → Kafka → orchestrators consume
Problem: Circular logic
  ├─ Orchestrator updates saga_state to FX_LOCKED
  ├─ Update triggers Kafka event
  ├─ Event triggers orchestrator to process same saga again
  ├─ Race condition between multiple orchestrators
  └─ Defeats purpose of explicit orchestration

Current approach (polling):
  ├─ Simpler (no extra systems)
  ├─ Pessimistic locks prevent conflicts automatically
  ├─ Cleaner model (orchestrator controls, doesn't react)
  └─ Acceptable latency for Lynx use case
```

Use CDC/Kafka for domain events (TransferHeld, RateLocked, etc.), not for saga state management.

### Pessimistic Locking with LIMIT

**Question: Can FOR UPDATE lock multiple rows in one query?**

**Answer: Yes.**

```sql
SELECT FROM saga_state 
WHERE status = 'HOLDING' 
LIMIT 100
FOR UPDATE;
```

This acquires exclusive locks on all 100 selected rows **atomically in a single operation**.

Other orchestrators executing the same query will WAIT until locks are released.

### Indexing for Performance

**Critical requirement: Index on (status, created_at)**

```sql
CREATE INDEX idx_saga_state_status_created 
ON saga_state(status, created_at);
```

**Why indexing matters:**

```
Without index (❌ BAD):
  SELECT ... WHERE status = 'HOLDING' LIMIT 100
  → Full table scan of 1M rows
  → Heavy DB load
  → Slow query

With index (✓ GOOD):
  SELECT ... WHERE status = 'HOLDING' LIMIT 100
  → Index lookup (instant)
  → Returns first 100 HOLDING sagas
  → Locks only 100 rows
  → Light DB load
  → Fast query (~1ms)
```

**Index strategy:**

```sql
-- Primary index (for orchestrator polling)
CREATE INDEX idx_saga_state_status_created 
ON saga_state(status, created_at);

-- Secondary index (for recovery worker)
CREATE INDEX idx_saga_state_status_updated 
ON saga_state(status, updated_at);
```

Orchestrator uses first (finds new sagas).
Recovery worker uses second (finds stuck sagas).

### Idempotency

Each orchestrator call is idempotent:

```
Call 1:
  orchestrator.lockFxRate(saga_id='uuid-1')
  → Updates saga_state SET status = 'FX_LOCKED'

Call 2 (retry):
  orchestrator.lockFxRate(saga_id='uuid-1')
  → saga_state already = 'FX_LOCKED'
  → No action (already processed)
  
Result: Safe to retry ✓
```

Guaranteed by:
- UNIQUE constraint on (saga_id, idempotency_key) in ledger
- SELECT saga_state WHERE saga_id = ? before each step
- Atomicity (same saga_id locked until step completes)

---

## Implementation Details: rate-lock expiry policy (DECIDED — release only, for now)

Worked through by direct Q&A, before `saga-orchestrator` or `fx-rate-service`'s
real provider integration exist. Once `fx-rate-service`'s `GET /v1/fx/quotes`
issues a rate with a TTL (other-docs/09 Decision 6) and `ledger-service`'s
`FxRateLock.expiresAt` stores it (other-docs/08 Decision 24), *something*
has to decide what happens when a saga is still sitting on an expired lock
when it's time to `settle`. Two candidate policies were considered:

### Option A: silently re-lock within the SAME saga — REJECTED for now

Get a fresh quote, write a SECOND `LOCK_DR`/`LOCK_CR` pair for the same
`sagaId`. Rejected for two independent reasons:

1. **Regulatory/consumer-trust, not just technical.** Most regulated
   payment/FX platforms (PSD2, UK FCA-style rules) require the customer be
   shown the actual rate before execution — silently substituting a
   different (possibly worse) rate after the fact, even automatically, is
   a real disclosure problem, not just an engineering inconvenience.
2. **A genuine, hard concurrency problem, not just complexity for its own
   sake.** `UNIQUE(userId, sagaId, entry_type, idempotency_key)` only
   catches a collision when the *idempotency key itself* matches. Two
   `saga-orchestrator` instances racing to relock the SAME saga (e.g. one
   crashes mid-request after sending its key, another later legitimately
   claims the same saga and mints its OWN new key) can both succeed —
   `idempotency_key` differing means the constraint doesn't fire, and you
   get a genuine double-lock (money moved into `FX_LOCK` twice). The
   `FOR UPDATE SKIP LOCKED` claiming mechanism above prevents this in the
   *normal* concurrent-polling case, but not the crash-then-reclaim case.
   Closing that gap properly needs a compare-and-swap/supersession pattern
   on the lock itself (mirroring the account-balance
   `UPDATE ... WHERE available >= ?` idiom) — real, addressable, but
   genuine added complexity for a feature not yet needed.

### Option B: fail and release, retry (if at all) as a brand-new saga — CHOSEN, for now

On an expired lock, `release` the existing saga fully (already-built,
already-safe compensation — no new mechanism). If a retry is wanted, it is
a **completely new saga**: fresh `sagaId`, fresh `Idempotency-Key`, as if
it were a brand-new user-initiated request — with a plain
`retriedFromSagaId` field on the new saga's `saga_state` row purely for
audit/traceability (not a uniqueness-bearing key, no contention on it).

This sidesteps Option A's concurrency problem entirely: a new `sagaId` has
no shared "current attempt" for two racing actors to fight over — the
same, already-proven single-saga idempotency protection just applies to
it fresh, with zero special-casing. The only sequencing care needed (not
a correctness bug, an ordering detail): the original saga's `release`
should complete before (or atomically with) the new saga's `hold`, so
funds aren't transiently double-held across both sagas.

**Status: Option B (release-and-new-saga) is the decided direction for
when this is eventually built.** `saga-orchestrator` doesn't exist yet, so
nothing here is implemented — this section exists so the reasoning and
the choice aren't re-litigated from scratch later. The "retry as a new
saga" half is explicitly **deferred** — not designed in detail, not
scheduled — only the release-on-expiry half is the settled behavior to
build first.

---

## Consequences

### Positive

- ✅ **Explicit control**: Saga logic visible in one place (easy to understand, debug, change)
- ✅ **Visibility**: Single query shows entire pipeline state
- ✅ **Deterministic recovery**: Clear what to retry and when
- ✅ **Easy compensation**: Rollback logic in one place, testable
- ✅ **Scalable**: Multiple orchestrators via pessimistic locking
- ✅ **Stateless orchestrator**: No orchestrator state, survives crashes
- ✅ **Easy to test**: Single code path to test, not distributed
- ✅ **No circular dependencies**: Orchestrator calls services, not vice versa

### Negative

- ❌ **Single point of failure**: If orchestrator down, sagas don't progress (mitigated by recovery worker)
- ❌ **Latency**: Polling adds 100ms (vs real-time events)
- ❌ **Polling overhead**: SELECT saga_state every 100ms (mitigated by partition pruning, indexing)

### Mitigations

| Consequence | Mitigation |
|---|---|
| Orchestrator downtime | Run 3+ instances (load-balanced). Recovery worker runs independently. |
| Polling latency | 100ms acceptable for 600ms transfer. Can upgrade to event-driven later. |
| Polling overhead | **Index on (status, created_at)** for orchestrator queries. **Index on (status, updated_at)** for recovery worker. With indexes, queries are ~1ms even on 1M sagas. Partition saga_state by date for very large tables. |
| Locking 100 rows simultaneously | FOR UPDATE LIMIT 100 acquires atomic locks on all 100 rows in single query. No issue. |

---

## Alternatives Considered

### Alternative 1: Event Choreography

Services subscribe to events, decide next step:

```
ledger-service publishes: TransferHeld
  ↓
fx-service subscribes, publishes: RateLocked
  ↓
account-service subscribes, publishes: TransferSettled
```

**Why rejected:**
- ❌ Implicit flow (must read all services to understand saga)
- ❌ Out-of-order delivery (Kafka doesn't order across topics)
- ❌ Distributed recovery (each service understands recovery differently)
- ❌ Hard to change flow (modify multiple services)
- ❌ Cascading failures (one slow service backs up pipeline)
- ❌ No visibility (must query 3 services to understand state)

See ADR-001 for detailed choreography analysis.

### Alternative 2: Temporal Workflows

Use a workflow engine (Temporal, Cadence) to manage saga:

```
@WorkflowMethod
public void transferWorkflow(TransferRequest req) {
  holdFunds(req);
  lockFxRate(req);
  settleFunds(req);
}
```

**Why rejected (for Lynx):**
- ❌ Additional dependency (Temporal cluster)
- ❌ Learning curve (new framework)
- ❌ Operational overhead (another system to maintain)
- ✓ Better for complex workflows (Lynx saga is simple: 3 steps)
- Consider for Phase 2+ if saga logic becomes complex

### Alternative 3: State Machine Service

Separate service for state management, each orchestrator queries it:

```
state-machine-service {
  "What's the next step?"
  → Returns: HOLD, LOCK, or SETTLE
}

orchestrator {
  nextStep = state-machine-service.getNextStep(saga_id)
  execute(nextStep)
}
```

**Why rejected:**
- ❌ Added RPC latency (state-machine-service)
- ❌ Distributed state (saga_state + state-machine state)
- ❌ No simplification (still polling saga_state)
- ✓ Not simpler than direct saga_state polling

---

## Related ADRs

- [ADR-001: Ledger-first Saga](ADR-001-ledger-first-saga.md) — Saga phases and pessimistic locking
- [ADR-002: Transactional Outbox](ADR-002-transactional-outbox.md) — How events are published
- [ADR-004: Idempotency via Idempotency-Key](ADR-004-idempotency.md) — **Resolved by a later decision, not built as originally planned here:** `ledger-service` removed its client-supplied `Idempotency-Key` entirely (migration V1, other-docs/08 Decision 29) — `(userId, sagaId, phase)` is the whole write identity now, so this orchestrator's redo loop needs no key-persistence mechanism at all; a redo is simply re-calling the same phase endpoint for the same `sagaId`. See other-docs/10's plan for the full correction. `fx-rate-service`'s `executionId` still needs the equivalent discipline this ADR originally described — see `SagaState`'s javadoc.
- [ADR-007: Internal Service-to-Service Authentication](ADR-007-internal-service-authentication.md) — **built, and `saga-orchestrator` is its first real caller** (other-docs/10): `ServiceTokenProvider`/`CircuitBreaker` authenticate every call to `ledger-service`/`fx-rate-service`, asserting `onBehalfOfUserId` per saga.

---

## References

- Saga Pattern: https://microservices.io/patterns/data/saga.html
- Orchestration vs Choreography: https://martinfowler.com/articles/patterns-of-distributed-systems/orchestrator.html
- Temporal Workflows: https://temporal.io/
- State Machines: https://en.wikipedia.org/wiki/Finite-state_machine

