# ADR-002: Transactional Outbox Pattern

**Status:** Accepted

**Date:** 2026-07-03

**Deciders:** Rajenthar

---

## Context

After ADR-001 decides that ledger is single source of truth, a critical question arises:

**How do we publish events to downstream services without losing them?**

Example scenario:
```
Saga-Orchestrator needs to:
  1. Write HOLD entries to ledger (postgres)
  2. Publish TransferHeld event to Kafka (notification, account-service)
```

**The Dual-Write Problem:**

```
Naive approach:
  1. INSERT ledger entry
  2. COMMIT
  3. Publish to Kafka

Problem: Process crashes between step 2 and 3
  ├─ Ledger has HOLD entry ✓
  ├─ Kafka never received event ✗
  └─ Notification-service never knows about transfer
```

**Or worse (write Kafka first):**

```
  1. Publish to Kafka
  2. INSERT ledger entry
  3. COMMIT

Process crashes between 1 and 2:
  ├─ Kafka has event ✓
  ├─ Ledger insert fails ✗
  └─ Account-service processes transfer that was never recorded
```

**The core issue: Two systems, one transaction boundary. Can't be atomic.**

---

## Decision

**Implement Transactional Outbox Pattern with Debezium CDC:**

```
Step 1: Write atomically
  BEGIN TRANSACTION
    INSERT ledger (saga_id, entry_type, amount, ...)
    INSERT outbox (saga_id, event_type, payload, created_at)
  COMMIT  ← both succeed or both fail

Step 2: CDC publishes asynchronously
  Debezium reads Postgres WAL (Write-Ahead Log)
  Extracts outbox entries
  Publishes to Kafka topics
  Marks outbox as published
```

**Architecture:**

```
┌─────────────────────────────┐
│   Saga-Orchestrator         │
│  (write service)            │
└──────────────┬──────────────┘
               │
               │ (1) INSERT ledger + outbox (same txn)
               ↓
        ┌──────────────┐
        │  Postgres    │
        │  ┌────────┐  │
        │  │ ledger │  │
        │  ├────────┤  │
        │  │ outbox │  │
        │  └────────┘  │
        └──────┬───────┘
               │
               │ (2) Writes to WAL (Write-Ahead Log)
               ↓
        ┌──────────────────┐
        │  Postgres WAL    │
        │  (binary log)    │
        └──────┬───────────┘
               │
               │ (3) CDC reads WAL in real-time
               ↓
        ┌──────────────────┐
        │  Debezium        │
        │  (CDC connector) │
        └──────┬───────────┘
               │
               │ (4) Publishes to Kafka
               ↓
    ┌──────────────────────┐
    │      Kafka           │
    │  ┌────────────────┐  │
    │  │ transfers      │  │
    │  │ notifications  │  │
    │  │ audits         │  │
    │  └────────────────┘  │
    └──────────────────────┘
               │
      ┌────────┴─────────┬──────────────┐
      ↓                  ↓              ↓
  account-service  notification-service audit-service
```

---

## Rationale

### 1. Atomic Writes (No Loss)

Outbox entry is written in **same transaction** as ledger:

```sql
BEGIN TRANSACTION (SERIALIZABLE);
  -- Ledger records the transfer
  INSERT INTO ledger (saga_id, entry_type, amount, account_id, ...)
    VALUES ('saga-uuid-1', 'HOLD_DR', 100, 'user-123', ...);
  
  -- Outbox records event to publish
  INSERT INTO outbox (saga_id, event_type, payload, created_at, status)
    VALUES ('saga-uuid-1', 'TransferHeld', '{"amount": 100, ...}', NOW(), 'PENDING');
COMMIT;  ← both success or both fail ✓
```

**Guarantees:**
- ✅ If ledger write succeeds, outbox entry exists (no lost events)
- ✅ If ledger write fails, outbox entry doesn't exist (no phantom events)
- ✅ No race condition between two systems

### 2. CDC (Change Data Capture) Handles Publishing

Debezium reads Postgres Write-Ahead Log (WAL) in real-time:

```
Postgres WAL (continuous append-only log):
  [INSERT ledger ...]
  [INSERT outbox ...]  ← Debezium sees this
  
Debezium processes:
  1. Read outbox entries from WAL
  2. Extract event payload
  3. Publish to Kafka
  4. Mark outbox as published/deleted

Advantages:
  ✓ No polling (real-time via WAL streaming)
  ✓ Exactly-once semantics (UNIQUE constraint + idempotency)
  ✓ Decoupled (outbox doesn't call Kafka directly)
  ✓ Ordered delivery (WAL is ordered)
```

### 3. Guaranteed Delivery (Exactly-Once)

```
Scenario: Debezium publishes to Kafka, then crashes

Attempt 1:
  Read: outbox with id=100, status=PENDING
  Publish: TransferHeld event to Kafka
  Crash: before marking as PUBLISHED

Attempt 2 (recovery):
  Read: outbox with id=100, status=PENDING (still pending!)
  Publish: TransferHeld event to Kafka (again)
  
Problem: Event published twice ❌

Solution: Idempotency-Key constraint
  Kafka deduplicates via idempotency-key
  account-service processes same event twice → idempotent (no double-debit)
```

### 4. Decoupling Services

Saga-orchestrator doesn't call Kafka directly:

```
TIGHTLY COUPLED (what we DON'T do):
  saga-orchestrator → ledger ✓
  saga-orchestrator → kafka ✓
  
  Problem: If Kafka is down, saga-orchestrator blocks ❌

LOOSELY COUPLED (what we do):
  saga-orchestrator → ledger + outbox (same transaction) ✓
  Debezium → kafka (eventual, async)
  
  Problem: If Kafka is down, saga-orchestrator continues ✓
```

### 5. Event Ordering Guaranteed

WAL is ordered. Events published in exact order they were written:

```
Ledger writes (ordered):
  1. HOLD_DR (T=0ms)
  2. HOLD_CR (T=1ms)
  3. LOCK_DR (T=100ms)
  4. LOCK_CR (T=101ms)
  5. SETTLE_DR (T=600ms)
  6. SETTLE_CR (T=601ms)

Outbox (one entry per ledger pair):
  1. outbox (TransferHeld) → published first
  2. outbox (RateLocked) → published second
  3. outbox (TransferSettled) → published third

Kafka consumers receive in order ✓
account-service processes HELD before LOCKED ✓
```

**Why Order Is Preserved:**

```
Kafka partitioning by saga_id:
  Partition 0: all saga_id=uuid-1 messages (HOLD, LOCK, SETTLE)
  Partition 1: all saga_id=uuid-2 messages
  Partition 2: all saga_id=uuid-3 messages

Kafka guarantee: Messages within same partition are ordered ✓
  Consumer reads Partition 0: [HOLD, LOCK, SETTLE] in order

Different sagas (different partitions) process in parallel:
  Saga-uuid-1 and Saga-uuid-2 process concurrently
  But each saga's messages maintain order ✓
```

**Critical: Single Topic for All Events**

```
SAFE (what we do):
  All outbox entries → single topic: ledger.public.outbox
  Partition key = saga_id
  Result: Order preserved per saga ✓

UNSAFE (what we avoid):
  HOLD entries → topic: transfers.hold
  LOCK entries → topic: transfers.lock
  SETTLE entries → topic: transfers.settle
  
  Problem: Kafka doesn't guarantee order across topics
    account-service might process SETTLE before HOLD ❌
```

### 6. Real-Time Streaming via Postgres Logical Replication

Debezium doesn't poll ledger like traditional consumers. Instead, it streams changes in real-time:

**Traditional Polling (Slow):**
```
account-service polling approach:
  Every 100ms: SELECT * FROM ledger WHERE created_at > last_read
  Worst-case latency: 100ms ❌

Debezium does NOT poll. Instead, Postgres pushes changes.
```

**Debezium Real-Time Streaming:**

```
1. Debezium creates a Postgres replication slot:
   CREATE_SLOT debezium_slot;

2. Debezium connects to the replication slot:
   SELECT * FROM pg_logical_slot_get_changes('debezium_slot', NULL, NULL);
   
3. Postgres streams WAL changes in real-time:
   On every COMMIT, Postgres pushes to Debezium:
     ├─ Transaction ID
     ├─ Table that changed (outbox)
     ├─ Column values (saga_id, event_type, payload)
     └─ Timestamp

4. Debezium receives immediately (no polling delay)
   Latency: 1-2ms from ledger write to Kafka publish ✓

5. Debezium publishes to Kafka topic
```

**Timing Comparison:**

```
Without Debezium (traditional polling):
  Ledger write: T=0ms
  account-service queries: T=0-100ms (polling interval)
  Event received: T=0-100ms
  Latency: 100ms worst-case ❌

With Debezium (real-time streaming):
  Ledger write: T=0ms
  WAL receives: T=0ms
  Postgres streams to Debezium: T=0-1ms
  Debezium publishes to Kafka: T=1-2ms
  Latency: 1-2ms ✓
```

**Why It's Real-Time (Not Polling):**

```
Key difference: Push vs Pull

Polling (Pull):
  Consumer: "Do you have anything new?"
  Database: "Let me check... (scanning overhead)"
  Result: Latency, wasted queries ❌

Logical Replication (Push):
  Database: "I just wrote HOLD entry to WAL"
  Automatically streams to Debezium
  Result: Instant notification, <2ms latency ✓
```

### 7. Repartitioning and Order Safety

**Scenario: What if Kafka repartitions (changes partition count)?**

```
Initial state (3 partitions):
  Partition 0: saga_id=uuid-1 messages
  Partition 1: saga_id=uuid-2 messages
  Partition 2: saga_id=uuid-3 messages

Repartitioning to 5 partitions:
  Partition 0: saga_id=uuid-2
  Partition 1: saga_id=uuid-3
  Partition 2: saga_id=uuid-1 (different partition now!)
  Partition 3: (empty)
  Partition 4: (empty)

Question: Will messages be out of order?
Answer: NO ✓
```

**Why Repartitioning Is Safe:**

```
1. Kafka rebalancing is ATOMIC
   ├─ All partitions stop simultaneously
   ├─ Consumer pauses reading
   └─ No messages are lost or reordered

2. Messages are transferred to new partitions
   └─ saga_id=uuid-1 messages move to Partition 2 (maintaining order)

3. Consumer reads from new partition (same offset semantics)
   └─ Reads where it left off (relative to new partition)

4. Consumer resumes reading
   └─ Gets same messages in same order
```

**Concrete Example:**

```
Before repartitioning:
  Partition 0: [HOLD_DR, HOLD_CR, LOCK_DR, LOCK_CR, SETTLE_DR, SETTLE_CR]
  Consumer read: offset=3 (just finished LOCK_DR)

Repartitioning occurs:
  Kafka rebalancing starts
  Consumer pauses at offset=3

  Messages for saga_id=uuid-1 → moved to Partition 2
  Partition 2: [HOLD_DR, HOLD_CR, LOCK_DR, LOCK_CR, SETTLE_DR, SETTLE_CR]
  Consumer offset: 3 (relative to new partition)

Consumer resumes:
  Reads Partition 2, offset=4
  Gets: LOCK_CR ← next message in order ✓
```

**Best Practices for Repartitioning:**

```
DO ✓
  ├─ Repartition during low-traffic windows
  ├─ Kafka handles rebalancing automatically
  ├─ Order per partition is always preserved
  └─ Use consistent partition key (saga_id)

DON'T ❌
  ├─ Split single topic into multiple topics (breaks ordering)
  ├─ Change partition key (breaks saga grouping)
  ├─ Manually redistribute partitions (let Kafka do it)
  └─ Assume global order across partitions
```

**Why Single Topic Matters:**

If we split events across multiple topics:
```
UNSAFE alternative:
  Topic: transfers.hold → [HOLD_DR, HOLD_CR]
  Topic: transfers.lock → [LOCK_DR, LOCK_CR]
  Topic: transfers.settle → [SETTLE_DR, SETTLE_CR]

Problem: Kafka doesn't guarantee order across topics
  account-service subscribes to all 3
  Could receive: SETTLE (topic 3), then HOLD (topic 1) ❌
  Would try to settle before holding ❌

SAFE (what we use):
  Topic: ledger.public.outbox (single topic, all events)
  Partition key: saga_id
  Result: All events for saga_id=uuid-1 in Partition N
          Delivered in order ✓
```

---

## Implementation Details

### Outbox Schema

```sql
CREATE TABLE outbox (
  id BIGSERIAL PRIMARY KEY,
  saga_id UUID NOT NULL,
  event_type VARCHAR(100) NOT NULL,  -- TransferHeld, RateLocked, TransferSettled
  payload JSONB NOT NULL,            -- event data
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  published_at TIMESTAMP,            -- null until Debezium publishes
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING'  -- PENDING, PUBLISHED
);

CREATE INDEX idx_outbox_status_created ON outbox(status, created_at);
```

### Debezium CDC Configuration

Debezium reads Postgres WAL and publishes to Kafka:

```
Postgres connector config:
  ├─ source.database.server.name: ledger-postgres
  ├─ table.include.list: public.outbox
  ├─ topic.prefix: ledger
  └─ transforms: filter to outbox table only

Kafka topic: ledger.public.outbox
  ├─ Partition key: saga_id (ensures ordering per saga)
  └─ Consumer groups: notification, account-service, audit-service
```

### Publishing Workflow

```
1. Saga-Orchestrator (transactional write)
   BEGIN;
     INSERT ledger (HOLD entries)
     INSERT outbox (event)
   COMMIT;

2. Debezium (continuous polling)
   POLL Postgres WAL:
     Sees: INSERT outbox (event_type=TransferHeld, ...)
     Publish to Kafka topic ledger.public.outbox
     Update: outbox SET status='PUBLISHED', published_at=NOW()

3. Kafka Consumers (event subscribers)
   notification-service:
     CONSUME: TransferHeld
     SEND email to user
   
   account-service:
     CONSUME: TransferHeld
     UPDATE account SET held_balance = held_balance + 100
     (idempotent via idempotency-key)
```

---

## Consequences

### Positive

- ✅ **Atomic writes**: Ledger + events always in sync (no orphaned entries)
- ✅ **Guaranteed delivery**: Events won't be lost (stored in outbox)
- ✅ **Exactly-once semantics**: Idempotency-key prevents duplicates
- ✅ **No message loss**: Even if Kafka is down, outbox persists
- ✅ **Ordered delivery**: WAL ensures FIFO order per saga
- ✅ **Loosely coupled**: Orchestrator doesn't depend on Kafka availability
- ✅ **Real-time**: Debezium publishes as entries appear in WAL

### Negative

- ❌ **Storage overhead**: Outbox table grows (1 entry per event)
- ❌ **Debezium dependency**: New operational complexity (another service)
- ❌ **WAL parsing**: Postgres WAL changes require Debezium updates
- ❌ **Cleanup**: Old outbox entries must be deleted/archived (housekeeping job)

### Mitigations

| Consequence | Mitigation |
|---|---|
| Storage growth | Time-partition outbox table. Delete published entries > 7 days old. |
| Debezium complexity | Use managed Debezium (Confluent Cloud). Or single instance (non-HA). |
| WAL changes | Debezium tracks schema evolution automatically. |
| Cleanup | Scheduled job: `DELETE FROM outbox WHERE published_at < NOW() - '7 days'::interval` |

---

## Alternatives Considered

### Alternative 1: Dual-Write (Write Both Directly)

```
saga-orchestrator {
  1. INSERT ledger
  2. Publish to Kafka
}
```

**Why rejected:**
- ❌ Race condition between steps 1 and 2
- ❌ If step 2 fails, no retry mechanism
- ❌ If orchestrator crashes, event lost
- ❌ No idempotency (Kafka doesn't know if already published)
- ❌ Tightly coupled (Kafka downtime blocks orchestrator)

### Alternative 2: Choreography (Events-First)

```
Publish to Kafka first:
  1. Publish TransferHeld to Kafka
  2. Write ledger entry

Problem: If step 2 fails, event is already published (orphaned)
```

**Why rejected:**
- ❌ Ledger and event order mismatch
- ❌ Events without ledger backing (ghost transfers)
- ❌ Recovery is impossible (event already published)

### Alternative 3: Message Queue (In-Process Queue)

Store events in process memory, publish in background:

```
List<Event> queue = [];

saga-orchestrator {
  1. INSERT ledger
  2. queue.add(event)  ← in-memory
  3. backgroundThread.publish(queue)
}
```

**Why rejected:**
- ❌ In-memory queue lost on crash
- ❌ No durability (unlike outbox which is in DB)
- ❌ Still needs retry logic (doesn't solve the problem)
- ❌ Scaling (one process = one queue)

### Alternative 4: Saga Pattern Without Events

Don't publish events, orchestrator polls saga state:

```
account-service {
  while true:
    SELECT * FROM saga_state WHERE status = 'SETTLED'
    UPDATE balance
}
```

**Why rejected:**
- ❌ Polling (latency, not real-time)
- ❌ Loose consistency (account-service always behind)
- ❌ Cascading delays (slow account-service slows saga)

---

## Related ADRs

- [ADR-001: Ledger-first Saga](ADR-001-ledger-first-saga.md) — Why ledger is source of truth
- [ADR-003: Saga Orchestrator Pattern](ADR-003-saga-orchestrator.md) — How orchestrator controls saga steps
- [ADR-004: Idempotency via Idempotency-Key](ADR-004-idempotency.md) — How to prevent duplicate event processing

---

## References

- Transactional Outbox: https://microservices.io/patterns/data/transactional-outbox.html
- Debezium CDC: https://debezium.io/
- Postgres Write-Ahead Log: https://www.postgresql.org/docs/current/wal-intro.html
- Exactly-Once Semantics: https://kafka.apache.org/documentation/#semantics
