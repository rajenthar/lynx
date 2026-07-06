# ADR-001: Ledger-first Saga Pattern

**Status:** Accepted

**Date:** 2026-07-03

**Deciders:** Rajenthar

---

## Context

Multi-currency transfers require atomic coordination across multiple systems:
- **Ledger Service**: Record the transfer (write side)
- **FX Rate Service**: Lock exchange rate (no slippage)
- **Account Service**: Update balances (read model)

A naive approach writes to multiple services simultaneously (dual-write):

```
Transfer Request
  ├─ Write HOLD to ledger
  ├─ Lock FX rate
  └─ Record transfer in account-service
```

**Problem:** If any service fails after a partial write, the system enters an inconsistent state with no way to recover deterministically.

Example: Write succeeds in ledger, fails in FX rate service → ledger has HOLD entry but no rate lock → orphaned transaction.

---

## Decision

**Implement a 3-step ledger-first saga with explicit state machine:**

```
1. HOLD phase:    Append HOLD entries to ledger (saga begins)
2. FX Lock phase: Lock rate, append LOCK entries
3. SETTLE phase:  Record final amounts, append SETTLE entries
```

**Key principle:** Ledger is the single source of truth. No concurrent writes to other services. Saga orchestrator reads ledger state, derives next step, and publishes events.

**Saga lifecycle on ledger:**

```
┌─────────────────────────────────────────────────────────┐
│ saga_state table (pessimistic lock)                     │
├─────────────────────────────────────────────────────────┤
│ saga_id │ status      │ created_at  │ updated_at        │
├─────────────────────────────────────────────────────────┤
│ uuid-1  │ HOLD        │ T0          │ T0                │
│ uuid-1  │ FX_LOCKED   │ T0          │ T1 (+200ms)       │
│ uuid-1  │ SETTLED     │ T0          │ T2 (+600ms)       │
└─────────────────────────────────────────────────────────┘
```

**Ledger entries by phase:**

| Phase | Entry Type | Debit Account | Credit Account | Amount | saga_id |
|-------|-----------|---------------|---|--------|---------|
| HOLD | HOLD_DR | User's Account | HOLD_POOL | 100 SGD | saga-uuid |
| HOLD | HOLD_CR | HOLD_POOL | — | 100 SGD | saga-uuid |
| FX_LOCKED | LOCK_DR | HOLD_POOL | FX_LOCK | 62 EUR | saga-uuid |
| FX_LOCKED | LOCK_CR | FX_LOCK | — | 62 EUR | saga-uuid |
| SETTLED | SETTLE_DR | FX_LOCK | Recipient | 62 EUR | saga-uuid |
| SETTLED | SETTLE_CR | Recipient | — | 62 EUR | saga-uuid |

---

## Rationale

### 1. Eliminates Dual-Write Race Condition

**Without ledger-first:**
```
Scenario: Write ledger succeeds, FX lock fails
├─ Ledger: HOLD recorded ✓
├─ FX Service: Lock fails ✗
└─ Recovery: What to undo? No idempotent marker.
```

**With ledger-first:**
```
Scenario: HOLD recorded, then FX lock fails
├─ Ledger: HOLD recorded (saga_state = HOLD) ✓
├─ FX Service: Lock fails ✗
└─ Recovery: Replay from saga_id, re-attempt LOCK phase ✓
```

The ledger entry is the idempotent marker. If retry happens with same saga_id, FX service rejects duplicate via `UNIQUE(saga_id, idempotency_key)` constraint.

### 2. Single Source of Truth

All transfer state lives in the ledger. No eventual consistency between ledger and saga_state:
- Query ledger → true state of the transfer
- Query account-service → stale cached balance (eventual consistency acceptable)
- Query saga_state → current step (needed for recovery)

Account service is purely a read model derived from ledger events. Never authoritative.

### 3. Automatic Recovery

Crash scenarios:

**Scenario A:** Process dies during HOLD phase
```
Recovery worker queries: saga_state WHERE status = HOLD AND updated_at < NOW() - 5min
Result: Finds orphaned saga, re-reads HOLD entries from ledger
Action: Advances to FX_LOCKED, publishes events
```

**Scenario B:** FX lock succeeds, but SETTLE fails
```
Recovery worker finds: saga_state WHERE status = FX_LOCKED AND updated_at < NOW() - 5min
Action: Re-publishes SETTLE command (idempotent via UNIQUE constraint)
```

No manual intervention needed. Recovery worker retries up to deadline.

### 4. Prevents Orphaned Transactions

Ledger is append-only. Once HOLD is recorded, state is durable:
- Network fails → HOLD persists
- Orchestrator crashes → HOLD persists
- FX service is down → HOLD persists (saga waits)

Recovery worker will eventually drive saga to completion or deadline.

### 5. Audit Trail

Every ledger entry is immutable. Compliance audit can replay entire saga from ledger:

```
SELECT * FROM ledger WHERE saga_id = 'uuid-1' ORDER BY created_at
```

Shows exact sequence: HOLD → LOCK → SETTLE with timestamps. No gaps, no deletions.

### 6. Concurrency Control: SERIALIZABLE Isolation + Pessimistic Locking

**The Problem Without Strong Isolation:**

```
Orchestrator-1 (READ COMMITTED level):
  READ saga_state (version = 1)
  UPDATE saga_state SET status = FX_LOCKED, version = 2

Orchestrator-2 (READ COMMITTED level, same time):
  READ saga_state (version = 1)  ← still sees old version!
  UPDATE saga_state SET status = FX_LOCKED, version = 2
  
Result: Both orchestrators modify same row ❌
        Conflict undetected, data corruption
```

**Our Solution: SERIALIZABLE + Pessimistic Lock**

```sql
SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;

BEGIN;
  -- Pessimistic lock: only one orchestrator at a time
  SELECT * FROM saga_state 
    WHERE saga_id = 'uuid-1' 
    FOR UPDATE;  ← acquires exclusive lock
  
  -- Safe to update (no race condition)
  UPDATE saga_state SET status = FX_LOCKED;
  
  -- Safe to insert ledger entries
  INSERT INTO ledger (saga_id, ...) VALUES (...);
  
  -- Safe to publish events
  INSERT INTO outbox (...);
COMMIT;  ← releases lock
```

**How SERIALIZABLE Works:**

SERIALIZABLE = "Transactions appear to execute serially (one at a time), even though they run in parallel."

```
Multiple orchestrators processing different saga_ids:
  Orchestrator-1: processes saga-uuid-1 (acquires lock)
  Orchestrator-2: processes saga-uuid-2 (acquires lock on different row)
  Orchestrator-3: processes saga-uuid-3 (acquires lock on different row)
  
Result: All 3 run in parallel ✓ (different locks)

Same saga processed by multiple orchestrators:
  Orchestrator-1: SELECT ... FOR UPDATE WHERE saga_id = 'uuid-1'
                  (acquires lock, processes)
  Orchestrator-2: SELECT ... FOR UPDATE WHERE saga_id = 'uuid-1'
                  (WAITS for Orchestrator-1's lock)
  
Result: Only 1 processes at a time ✓ (pessimistic lock)
```

**Why SERIALIZABLE (not weaker isolation levels)?**

```
READ UNCOMMITTED:
  ├─ Allows dirty reads (see uncommitted changes)
  ├─ Pessimistic lock doesn't protect
  └─ Risk: Data corruption ❌

READ COMMITTED:
  ├─ Allows phantom reads (new rows appear between reads)
  ├─ Example: Query finds 1000 sagas, new 100 inserted,
  │           next query within same txn sees 1100
  └─ Risk: Stale reads, missed updates ❌

REPEATABLE READ:
  ├─ Allows serialization anomalies
  ├─ Complex multi-row updates see partial state
  └─ Risk: Inconsistent transfers ❌

SERIALIZABLE (what we use):
  ├─ No dirty reads, no phantom reads, no anomalies
  ├─ Pessimistic lock enforced
  └─ Safe: Conflict-free concurrent execution ✓
```

**Scaling to Multiple Orchestrators:**

```
Load: 1M transfers/day = ~12 transfers/second

Single Orchestrator:
  Latency: 600ms per saga
  Max throughput: ~1600 sagas/sec
  But only processes 1 saga at a time (sequential)

3 Orchestrators (load-balanced):
  Throughput: 3x higher (process 3 different saga_ids in parallel)
  Latency: 600ms per saga (unchanged)
  
Scaling: No global bottleneck ✓
  ├─ Different saga_ids lock different rows
  ├─ Each orchestrator runs independently
  └─ Postgres handles lock queuing automatically

Bottleneck only if:
  ├─ 1000 concurrent requests for SAME saga_id (unlikely)
  │  (single transfer, only one client initiates)
  └─ Or retry storm (same saga retried 100x)
```

**Polling vs Event-Driven Discovery:**

Current design uses polling:
```
Orchestrator loop:
  1. POLL: SELECT * FROM saga_state WHERE status = 'HOLD' LIMIT 100
  2. PROCESS: For each saga, execute 3-phase
  3. WAIT: Sleep 100ms (polling interval)
  4. GOTO 1

Behavior:
  New saga arrives at T=100ms
  Next poll at T=200ms
  Processing starts at T=200ms
  Delay: ~100ms (polling interval)
  
  If 100 new sagas arrive during T=0ms batch processing:
  → Discovered in next polling cycle
  → Not starvation (they WILL be processed)
  → Just batched into next iteration
```

Why polling (not event-driven)?
- ✓ Simpler (no message queue needed)
- ✓ Fewer dependencies (stateless orchestrator)
- ✓ Easier to scale (add more orchestrators)
- ✓ Acceptable latency (~100ms polling + 600ms processing)

Tradeoff: Slight delay vs operational simplicity ✓

---

## Consequences

### Positive

- ✅ Dual-write race condition eliminated
- ✅ Automatic recovery without manual intervention
- ✅ Audit trail built-in (immutable ledger)
- ✅ Single source of truth (ledger is authoritative)
- ✅ Eventual consistency acceptable for read models (not critical path)
- ✅ Deterministic compensation (know exact saga state always)

### Negative

- ❌ Latency: 3 sequential phases (HOLD → LOCK → SETTLE) adds ~600ms vs atomic instant write
- ❌ Complexity: Saga orchestrator must handle retries, timeouts, deadlines
- ❌ Storage: Ledger grows fast (6 entries per transfer, millions per day)
- ❌ Query complexity: Must JOIN saga_state + ledger for full transfer state

### Mitigations

| Consequence | Mitigation |
|---|---|
| Latency (600ms) | Acceptable for production transfers. P95 < 1.2s is target. |
| Orchestrator complexity | Recovery worker is async, not on critical path. |
| Storage growth | Ledger is time-partitioned (native Postgres feature). Archive old monthly partitions. Example: `CREATE TABLE ledger_2026_07 PARTITION OF ledger FOR VALUES FROM ('2026-07-01') TO ('2026-08-01')` |
| Query complexity | Account-service caches results (read-through cache). |
| Pessimistic lock contention | Multiple orchestrators scale horizontally (lock only per saga_id, not global). Different saga_ids process in parallel. Only same-saga retries queue. |
| Polling latency | 100-500ms polling interval acceptable. Trade-off: simplicity vs event-driven complexity. Can upgrade to event-driven later if needed. |

### Isolation Level Guarantees

Postgres SERIALIZABLE provides:
- **No dirty reads**: Can't see uncommitted changes
- **No phantom reads**: New rows won't appear between reads of same query
- **No serialization anomalies**: Complex multi-row updates see consistent state
- **Conflict detection**: Automatic detection of concurrent conflicts
- **Pessimistic lock enforcement**: FOR UPDATE locks respected across all isolation levels

For financial systems, SERIALIZABLE is not optional—it's mandatory.

---

## Alternatives Considered

### Alternative 1: Choreography (Event-Driven Saga)

Each service subscribes to events and publishes next step:

```
ledger-service publishes: TransferHeld (event-1)
  ↓ [asynchronous, no guarantee when delivered]
fx-service subscribes, publishes: RateLocked (event-2)
  ↓ [asynchronous, no guarantee when delivered]
account-service subscribes, publishes: TransferSettled (event-3)
```

**Problem 1: Out-of-Order Message Delivery**

Kafka/message queues don't guarantee order:
```
Events published in order: HELD → LOCKED → SETTLED
But messages may arrive out-of-order:

Scenario A (wrong order):
  account-service receives SETTLED (event-3) first
    → tries to SETTLE before LOCK exists
    → foreign key error, transfer fails ❌

Scenario B (retry causes duplicate):
  fx-service publishes LOCKED
  crashes before marking as processed
  Kafka retries LOCKED again
  account-service processes LOCKED twice
  → duplicate SETTLE entries ❌
```

**Problem 2: No Way to Enforce Ordering**

Even with careful design:
```
Orchestration (what we use):
  Orchestrator reads saga_state
  Explicitly commands: "HOLD first"
  WAITS for HOLD response
  Explicitly commands: "LOCK second"
  WAITS for LOCK response
  Result: Order guaranteed ✓

Choreography:
  Services subscribe independently
  No synchronization point
  No way to say: "Wait, don't process SETTLE until LOCK is done"
  Services race each other
  Result: Order uncertain ❌
```

**Problem 3: Loss of Visibility**

```
Choreography:
  ledger-service published HELD
  fx-service consumed HELD, processed, published LOCKED
  account-service consumed LOCKED, processing, publishing SETTLED
  
  Question: Where is this transfer now?
  → No single answer
  → Must query 3 services, stitch together state
  → Complex debugging ❌

Orchestration:
  SELECT * FROM saga_state WHERE saga_id = 'uuid-1'
  Result: status = FX_LOCKED
  Answer: Single source of truth ✓
```

**Problem 4: Recovery is Distributed**

```
Choreography failure scenario:
  HELD published ✓
  LOCKED published ✓
  SETTLED publish fails ❌
  
  Recovery question: Who retries SETTLED?
  → account-service? (doesn't know it was supposed to)
  → fx-service? (already published, no idea SETTLE failed)
  → separate recovery service? (needs to understand all 3 services' logic)
  
  Result: Recovery logic scattered across services ❌

Orchestration:
  saga_state = SETTLED (in DB)
  Recovery worker queries: saga_state WHERE status = SETTLED AND updated_at < NOW() - 5min
  Re-publishes SETTLE event
  All recovery in one place ✓
```

**Problem 5: Cascading Failures**

```
Choreography chain:
  HELD depends on ledger-service
  LOCKED depends on fx-service (which waits for ledger-service)
  SETTLED depends on account-service (which waits for fx-service)
  
  If ledger-service is slow:
    → HELD is delayed
    → fx-service waits for HELD
    → account-service waits for LOCKED
    → Entire pipeline backs up
    → Single point of failure ❌

Orchestration:
  Orchestrator buffers sagas in saga_state table
  If fx-service is slow:
    → Orchestrator waits for FX_LOCKED phase
    → But other sagas can be processed (different saga_ids)
    → Isolation: one slow service doesn't block others ✓
```

**Why rejected:**
- ❌ Out-of-order message delivery causes race conditions
- ❌ Duplicates from retries corrupt state
- ❌ No way to enforce HOLD → LOCK → SETTLE ordering
- ❌ Loss of visibility (no central saga state)
- ❌ Recovery logic distributed, complex to debug
- ❌ Cascading failures (one slow service blocks pipeline)

### Alternative 2: Distributed Lock (Optimistic Saga)

Use version numbers to prevent race conditions:

```
saga_state:
  saga_id | status | version
  uuid-1  | HOLD   | 1
  
UPDATE saga_state SET status = FX_LOCKED, version = 2 WHERE saga_id = uuid-1 AND version = 1
```

**Why rejected:**
- Optimistic conflict detection (must handle version mismatch)
- Contention on saga_state causes retries (thrashing)
- Still requires retry logic, recovery logic
- No better than pessimistic lock for heavily-contended resource

### Alternative 3: 2-Phase Commit (XA Transactions)

Coordinate across databases with prepare/commit:

```
Prepare: lock accounts in both ledger and fx-service
Commit: write to both
```

**Why rejected:**
- Requires distributed transaction support (expensive)
- Blocks resources during prepare phase (poor latency)
- Cascading failures (if one service is slow, entire system blocks)
- Not applicable to microservices (separate databases)

---

## Related ADRs

- [ADR-002: Transactional Outbox Pattern](ADR-002-transactional-outbox.md) — How events are published atomically with ledger writes
- [ADR-003: Saga Orchestrator Pattern](ADR-003-saga-orchestrator.md) — Explicit orchestration vs choreography
- [ADR-004: Idempotency via Idempotency-Key](ADR-004-idempotency.md) — How to prevent duplicate sagas

---

## References

- Saga Pattern: https://microservices.io/patterns/data/saga.html
- Idempotent APIs: https://stripe.com/blog/idempotency
- Event Sourcing: https://martinfowler.com/eaaDev/EventSourcing.html
