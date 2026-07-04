# ADR-005: account-service as Pure CQRS Read Model

**Status:** Accepted

**Date:** 2026-07-04

**Deciders:** Rajenthar

---

## Context

Users constantly ask one question: **"What is my balance?"**

This is by far the most frequent query in the system:

```
Traffic profile (typical):
  Balance reads:     ~10,000 requests/sec  (every app open, every screen refresh)
  Transfers writes:  ~100 requests/sec     (actual money movement)

Read:Write ratio ≈ 100:1
```

The ledger (ADR-001) is the single source of truth. The naive way to answer
a balance query is to compute it from the ledger:

```sql
SELECT SUM(
  CASE WHEN entry_type LIKE '%_CR' THEN amount
       WHEN entry_type LIKE '%_DR' THEN -amount
  END
)
FROM ledger
WHERE account_id = 'user-account-123';
```

**Why this breaks at scale:**

```
Problem 1: Ledger grows forever (append-only)
  1 year of entries: ~2 billion rows
  SUM over millions of rows per account per query: 100ms-2s ❌

Problem 2: Read traffic crushes the write side
  10,000 balance queries/sec hitting the ledger DB
  → Contends with saga writes (the critical path!)
  → Transfer latency degrades because someone refreshed their app ❌

Problem 3: Can't optimize for both
  Ledger is optimized for: append-only writes, SERIALIZABLE isolation
  Balance queries want: indexed point-reads, caching, denormalization
  One database can't be shaped for both at once ❌
```

---

## Decision

**Implement account-service as a pure CQRS read model — a balance projection
built entirely from ledger events, never written to directly.**

CQRS = Command Query Responsibility Segregation:
- **Command side (write):** ledger-service — the only place money moves
- **Query side (read):** account-service — a derived, disposable view

```
                        WRITE SIDE (source of truth)
┌────────────┐   saga    ┌────────────────┐
│ saga-      │──────────▶│ ledger-service │
│ orchestr.  │           │  Postgres      │
└────────────┘           │  ledger+outbox │
                         └───────┬────────┘
                                 │ WAL
                                 ▼
                         ┌────────────────┐
                         │   Debezium     │  (ADR-002)
                         └───────┬────────┘
                                 │ events
                                 ▼
                         ┌────────────────┐
                         │     Kafka      │
                         │ TransferHeld   │
                         │ RateLocked     │
                         │ TransferSettled│
                         └───────┬────────┘
                                 │ consume
                        READ SIDE (derived view)
                         ┌───────▼────────┐
                         │ account-service│
                         │  balances table│
                         │  + Redis cache │
                         └───────┬────────┘
                                 │
                     GET /v1/accounts/{id}/balance
                                 │
                                 ▼
                              Client
```

**account-service maintains a projection:**

```sql
CREATE TABLE account_balances (
  account_id UUID NOT NULL,
  currency CHAR(3) NOT NULL,
  available_balance DECIMAL(18,2) NOT NULL DEFAULT 0,  -- spendable now
  held_balance DECIMAL(18,2) NOT NULL DEFAULT 0,        -- reserved by in-flight sagas
  last_event_id BIGINT NOT NULL,                        -- for idempotent event processing
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  PRIMARY KEY (account_id, currency)
);
```

**Event handlers update the projection:**

```
TransferHeld (100 SGD):
  available_balance -= 100
  held_balance      += 100

TransferSettled:
  held_balance      -= 100      (source side)
  available_balance += 62 EUR   (recipient side)

TransferFailed (compensation):
  held_balance      -= 100
  available_balance += 100      (money returned)
```

Balance queries become a single indexed point-read (or Redis cache hit):

```sql
SELECT available_balance, held_balance
FROM account_balances
WHERE account_id = ? AND currency = ?;
-- <1ms, zero load on the ledger ✓
```

---

## Rationale

### 1. Reads Scale Independently of Writes

```
Ledger DB load:
  Before CQRS: 100 writes/sec + 10,000 reads/sec  ← reads dominate ❌
  After CQRS:  100 writes/sec + 0 balance reads   ← writes only ✓

account-service load:
  10,000 reads/sec against an indexed projection + Redis cache
  Scale horizontally: add replicas of account-service + read replicas of its DB
  Zero impact on transfer latency ✓
```

The critical money path (saga) and the read path never compete for the
same database again.

### 2. Eventual Consistency Is Acceptable Here — By Design

The projection lags the ledger slightly (Debezium + Kafka + consumer ≈ tens of ms):

```
T=0ms:   SETTLE written to ledger        ← money HAS moved (truth)
T=5ms:   Debezium publishes TransferSettled
T=15ms:  account-service consumes, updates projection
T=0-15ms window: balance query returns the OLD balance
```

**Why this is fine:**

```
What eventual consistency affects:
  └─ Display balance shown in the app (cosmetic, self-corrects in ms)

What it does NOT affect:
  └─ Money movement decisions.
     The saga checks "sufficient funds?" against the LEDGER
     (write side, SERIALIZABLE) — never against the projection.
     An overspend is impossible no matter how stale the read model is. ✓
```

This is the key CQRS discipline: **the read model is never consulted for
correctness decisions.** It exists only to answer questions fast.

### 3. Idempotent Event Processing (Kafka Delivers At-Least-Once)

Kafka may redeliver events (consumer crash before offset commit). The
projection must not double-apply:

```sql
-- Each event carries the outbox id (monotonic per partition)
UPDATE account_balances
SET available_balance = available_balance - 100,
    held_balance      = held_balance + 100,
    last_event_id     = :event_id
WHERE account_id = :account_id
  AND currency = :currency
  AND last_event_id < :event_id;   -- ← already applied? affected rows = 0, skip ✓
```

```
Redelivery scenario:
  Event id=500 applied, last_event_id = 500
  Consumer crashes before committing Kafka offset
  Kafka redelivers event id=500
  UPDATE ... WHERE last_event_id < 500 → 0 rows affected
  → No double-debit of the projection ✓
```

Ordering per account is guaranteed because events are partitioned by
saga_id and applied per account sequentially (ADR-002).

### 4. The Projection Is Disposable (Rebuild from Ledger)

Because account-service holds **no original data**, it can be destroyed and
rebuilt at any time:

```
Rebuild procedure (disaster recovery / bug in projection logic):
  1. TRUNCATE account_balances
  2. Replay: either re-consume Kafka topic from offset 0,
     or recompute directly from ledger:
       INSERT INTO account_balances
       SELECT account_id, currency, SUM(...), ...
       FROM ledger GROUP BY account_id, currency
  3. Resume consuming from the recorded position

Nothing is lost — the ledger was always the truth ✓
```

This also means a projection bug is an inconvenience, not a data-loss
incident. Fix the handler, rebuild, done.

### 5. Read-Optimized Shape (Impossible on the Write Side)

The projection can be shaped purely for queries:

```
Optimizations available on the read side:
  ✓ One row per (account, currency) — point reads, no SUM
  ✓ Redis cache in front (TTL seconds, invalidated on event apply)
  ✓ Postgres read replicas for horizontal read scaling
  ✓ Denormalized fields (currency, display name) — no JOINs

None of these are possible on the append-only, SERIALIZABLE ledger.
```

### 6. Reconciliation Catches Drift

Trust but verify — reconciliation-service periodically compares the two sides:

```
Nightly (and sampled hourly):
  ledger_sum  = SELECT SUM(...) FROM ledger GROUP BY account_id, currency
  projection  = SELECT * FROM account_balances

  For each account:
    if ledger_sum != projection:
      → ALERT + auto-rebuild that account's projection
      → Root-cause the missed/double-applied event

Drift expected: zero. Any drift is a bug, and it is DETECTABLE. ✓
```

---

## Consequences

### Positive

- ✅ **Balance reads <1ms** (point-read + cache) instead of ledger SUM scans
- ✅ **Ledger protected**: zero read traffic on the money-critical write path
- ✅ **Independent scaling**: add account-service replicas without touching ledger
- ✅ **Disposable projection**: rebuildable from ledger/Kafka at any time
- ✅ **Multi-currency native**: one projection row per (account, currency)
- ✅ **Drift detectable**: reconciliation-service verifies projection vs ledger

### Negative

- ❌ **Eventual consistency**: balance display can lag ~10-50ms behind reality
- ❌ **More moving parts**: consumer, projection DB, cache, reconciliation
- ❌ **Duplicate handling required**: at-least-once delivery demands idempotent handlers
- ❌ **Two databases**: operational cost of the projection store

### Mitigations

| Consequence | Mitigation |
|---|---|
| Stale balance display | Lag is tens of ms — below human perception for app UIs. UI can also optimistically apply the user's own pending transfer. |
| Consumer falls behind | Monitor Kafka consumer lag; alert if > 5s. Scale consumer instances (partitions allow parallelism). |
| Duplicate events | `last_event_id` guard makes every handler idempotent (Section 3). |
| Projection bug/corruption | Rebuild from ledger — it is a derived view, never the truth. |

---

## Alternatives Considered

### Alternative 1: Query the Ledger Directly (No Read Model)

```sql
SELECT SUM(...) FROM ledger WHERE account_id = ?
```

**Why rejected:**
- ❌ SUM over millions of rows per query (100ms-2s at scale)
- ❌ 10,000 reads/sec contend with saga writes on the same DB
- ❌ Ledger indexes/isolation are shaped for writes, not point-reads

### Alternative 2: Balance Column Updated in the Saga (Synchronous Dual-Write)

Update `account_balances` inside the saga transaction itself.

**Why rejected:**
- ❌ Couples read model to the write path — every transfer now waits on
  balance-table locks (hot accounts serialize all their transfers)
- ❌ Puts read traffic and write traffic back on one database
- ❌ Cross-service dual-write if balances live in another service (the exact
  race ADR-002 exists to eliminate)

### Alternative 3: Materialized View in Postgres

```sql
CREATE MATERIALIZED VIEW account_balances AS SELECT SUM(...) FROM ledger ...;
REFRESH MATERIALIZED VIEW account_balances;  -- periodically
```

**Why rejected:**
- ❌ REFRESH recomputes the whole view — minutes of staleness, heavy scans
- ❌ Still runs on the ledger database (read load not isolated)
- ❌ No incremental updates in vanilla Postgres

### Alternative 4: Cache-Only (Redis as the Balance Store)

**Why rejected:**
- ❌ Redis is in-memory: a restart wipes RAM and it comes back EMPTY ("cold") —
  every account balance would need recomputing from the ledger before serving anyone
- ❌ Redis persistence options don't close the gap for money data:
  - RDB snapshots: lose everything since the last snapshot (minutes) on crash
  - AOF (Append Only File — command log replayed on restart, same idea as
    Postgres WAL): default `everysec` fsync still loses ~1s of writes;
    `always` fsync destroys the speed that is Redis's whole point
- ❌ No SQL for reconciliation/audit queries, no SERIALIZABLE transactions
- ✓ We DO use Redis — but in FRONT of the Postgres projection, not instead of it.
  If Redis dies, requests fall through to Postgres and the cache re-warms
  automatically. Same principle as ADR-004: cache for speed, database for
  durability/correctness.

---

## Related ADRs

- [ADR-001: Ledger-first Saga](ADR-001-ledger-first-saga.md) — the write side this model derives from
- [ADR-002: Transactional Outbox](ADR-002-transactional-outbox.md) — how events reach the read side (ordering, delivery)
- [ADR-004: Idempotency](ADR-004-idempotency.md) — same at-least-once discipline, applied to consumers here

---

## References

- CQRS: https://martinfowler.com/bliki/CQRS.html
- Event-driven projections: https://microservices.io/patterns/data/cqrs.html
- Kafka consumer semantics: https://kafka.apache.org/documentation/#semantics
