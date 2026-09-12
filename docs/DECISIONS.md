# Lynx — Decision Summary (Quick Reference)

One-page reference of every architectural decision. Full reasoning lives in the individual ADRs — this file is for quick lookup during development.

---

## The System in One Paragraph

Multi-currency transfers execute as a **3-step ledger-first saga** (HOLD → FX_LOCK → SETTLE) controlled by a **central orchestrator**. The **append-only ledger in Postgres is the single source of truth**; every write also inserts an **outbox event in the same transaction**, which **Debezium streams from the Postgres WAL to Kafka topics**. Read models (**account-service** balances) are **projections built from those events** — fast, eventually consistent, disposable. **Duplicates are impossible** end-to-end: client Idempotency-Key → deterministic saga_id → DB UNIQUE constraints.

---

## Services Built So Far (tracked so nothing gets missed)

Every real, built-and-tested module in this repo, its status, and where its
full writeup lives — kept current so no service's existence or state has to
be re-derived from scratch.

| Module | Status | Docs |
|---|---|---|
| `lynx-common` | Built, tested | [other-docs/01](other-docs/01-lynx-common-design-decisions.md) |
| `lynx-money` | Built, tested | [other-docs/02](other-docs/02-lynx-money-design-decisions.md) |
| `lynx-security` | Built, tested (22 tests) — `JwtVerifier`, `CorrelationIdFilter`, `ServiceTokenProvider` (its token-fetch guard migrated from a hand-rolled `CircuitBreaker`, since deleted, to resilience4j) | [other-docs/03](other-docs/03-lynx-security-design-decisions.md) |
| `lynx-idempotency` | Built, tested (`IdempotencyGuard`, `InMemoryIdempotencyCache`, `RedisIdempotencyCache`) | [other-docs/04](other-docs/04-lynx-idempotency-design-decisions.md) |
| `lynx-events` | Built, tested | [other-docs/05](other-docs/05-lynx-events-design-decisions.md) |
| `lynx-telemetry` | Built, tested | [other-docs/06](other-docs/06-lynx-telemetry-design-decisions.md) |
| `ledger-service` | Built, tested (15 tests) — HOLD/LOCK/SETTLE/RELEASE + audit trail + locked-rate read, real Postgres via Testcontainers | [other-docs/08](other-docs/08-ledger-service-design-decisions.md) |
| `fx-rate-service` | Built, tested (14 tests) — idempotent FX execution + rate quoting (with TTL), both behind mock providers; no real caller or real provider yet | [other-docs/09](other-docs/09-fx-rate-service-design-decisions.md) |
| `auth-service` | Not started | — |
| `saga-orchestrator` | Built, tested (25 tests) — HOLD → QUOTE+LOCK → EXECUTE → SETTLE polling loop, `FOR UPDATE SKIP LOCKED` proven against real Postgres, `ServiceTokenProvider`'s first real caller; recovery worker; `saga_state` scoped by `(user_id, saga_id)` not `saga_id` alone; each downstream client has its own resilience4j `CircuitBreaker`, a separate named instance from `ServiceTokenProvider`'s own (now also resilience4j — `lynx-security`'s hand-rolled `CircuitBreaker` was deleted entirely); `transaction-service` (not built) is its only intended caller | [other-docs/10](other-docs/10-saga-orchestrator-plan.md) |
| `account-service` | Not started | — |
| everything else in `services/*` (12 more placeholder dirs) | Not started | — |

---

## Decision Index

| # | Decision | Instead of | Why (one line) |
|---|---|---|---|
| [ADR-001](ADR-001-ledger-first-saga.md) | Ledger-first 3-step saga | Dual-write to multiple services | Ledger entry is the durable, idempotent marker; recovery is deterministic |
| [ADR-002](ADR-002-transactional-outbox.md) | Transactional outbox + Debezium CDC | Publishing to broker directly from services | Same-transaction insert = events can never be lost or orphaned |
| [ADR-003](ADR-003-saga-orchestrator.md) | Central saga orchestrator (polling) | Event choreography | Explicit flow; single place for state, recovery, and compensation |
| [ADR-004](ADR-004-idempotency.md) | Idempotency-Key + deterministic saga_id | Random saga_id, cache-only dedup | DB UNIQUE constraint is the safety net that survives crashes |
| [ADR-005](ADR-005-cqrs-read-model.md) | account-service as pure CQRS projection | Querying ledger for balances | Reads scale independently; ledger write path stays untouched |
| [ADR-007](ADR-007-internal-service-authentication.md) | Service identity + on-behalf-of userId (ACCEPTED — built and tested in `lynx-security`/`ledger-service`; `auth-service` issuing these tokens is the only piece still open) | Replaying the user's own JWT through every saga phase | A saga's own live JWT expires long before the saga finishes; service calls must authenticate as the service, not impersonate the user |

---

## Core Rules (Apply Everywhere)

1. **Ledger is the only source of truth.** Money decisions (sufficient funds, saga progress) are NEVER made from read models or caches.
2. **Cache for speed, database for correctness.** The system must remain fully correct with every cache wiped.
3. **All consumers/handlers are idempotent.** The broker delivers at-least-once; every handler must tolerate redelivery.
4. **Never bypass the outbox.** No service publishes to the broker directly — events flow only via ledger-transaction → outbox → Debezium.
5. **Correctness decisions run under SERIALIZABLE + pessimistic locks** on the write side.
6. **Compensation is explicit.** Rollback logic lives in the orchestrator, not scattered across services.

---

## Domain Model

- Users hold **multiple accounts**; each account holds **balances in multiple currencies** (dynamic currency support)
- Balances are tracked per `(account_id, currency)` — one projection row each
- Double-entry bookkeeping: every movement has DR + CR legs, invariant `sum(DR) = sum(CR)` always

---

## Saga (ADR-001, ADR-003)

**Phases:** `HOLDING → FX_LOCKED → SETTLED` (or `FAILED` + compensation from any step)

**Ledger entries per phase:** HOLD_DR/HOLD_CR → LOCK_DR/LOCK_CR → SETTLE_DR/SETTLE_CR

**Orchestration pattern: centralized (orchestrator commands services), NOT choreography.** Rejected because choreography gives out-of-order delivery across topics, duplicates from retries, no enforced ordering, no central visibility, distributed recovery logic, and cascading failures.

**Orchestrator loop (per phase, batched, locked):**
```sql
SELECT * FROM saga_state
WHERE status = 'HOLDING'          -- one query PER PHASE (never combined + re-checked)
ORDER BY created_at               -- deterministic scan order — see the SKIP LOCKED note below
LIMIT 100                         -- batching: pipeline continuously, don't drain 1M rows first
FOR UPDATE SKIP LOCKED;           -- pessimistic lock: whole batch claimed atomically, no blocking
```
- **`FOR UPDATE SKIP LOCKED`, not plain `FOR UPDATE` (fixed — found by inspection, tracked so it
  doesn't regress):** the two aren't interchangeable, and this doc previously showed plain
  `FOR UPDATE` while claiming "waiting instances get the NEXT batch" — that specific behavior
  is what `SKIP LOCKED` delivers, not plain `FOR UPDATE`. Without it, if two orchestrator
  instances' queries match an overlapping row set (nothing prevented that either, absent the
  `ORDER BY` — now added), the second instance BLOCKS and WAITS on those specific rows until the
  first transaction commits, rather than moving on to different, still-available work. That
  wait doesn't cause double-processing (Postgres re-checks the `WHERE` against the row's
  now-current state once unblocked, so an already-claimed-and-updated row correctly falls out of
  the result — `ledger-service`'s own idempotency handling is a backstop for the rare case this
  still races, e.g. a crash mid-processing after claiming, not the routine path), but it IS
  wasted time: the whole point of horizontally scaling the orchestrator is instances doing
  DIFFERENT work in parallel, not one blocking on rows another already grabbed.
  `SKIP LOCKED` makes a blocked instance skip straight past already-locked rows to the next
  available ones instead — genuinely zero-wait, zero-overlap batch claiming. The added
  `ORDER BY created_at` gives concurrent scans a consistent, deterministic order so their
  batches are far more likely to be disjoint in the first place, rather than relying on
  `SKIP LOCKED` alone to sort out overlap after the fact.
- **Multiple stateless orchestrator instances (3+)** scale linearly with zero coordination code — Postgres row locks do the distribution
- **Polling interval ~100ms** — acceptable vs 600ms saga latency; phantom rows (new sagas arriving mid-batch) are simply picked up next iteration (batching, not starvation)
- **No CDC/events on saga_state** — that would be circular (orchestrator reacting to its own updates) and reintroduce race conditions. CDC is for domain events only.
- **Isolation level: SERIALIZABLE, mandatory** — weaker levels allow dirty reads (READ UNCOMMITTED — locks not even respected), phantom reads (READ COMMITTED), or serialization anomalies (REPEATABLE READ)
- **Pessimistic over optimistic locking** — saga_state is heavily contended; version-check retries would thrash. Lock-and-wait is calmer and needs no retry logic.

**Required indexes (without them: full table scans on 1M+ rows):**
```sql
CREATE INDEX idx_saga_state_status_created ON saga_state(status, created_at);  -- orchestrator polling
CREATE INDEX idx_saga_state_status_updated ON saga_state(status, updated_at);  -- recovery worker
```

**Recovery worker (independent process):** finds sagas with `updated_at < NOW() - 5 min`, resumes the next step based on current status. Every step is idempotent → safe to retry. SLA: stuck saga resumed < 15 min.

**Compensation (in orchestrator, explicit):** on failure — release hold, unlock FX rate, set status FAILED, publish TransferFailed.

**Ledger storage:** append-only, immutable, time-partitioned monthly (native Postgres `PARTITION BY RANGE (created_at)` — logical routing, no data copying); old partitions archived then dropped. Full audit replay: `SELECT * FROM ledger WHERE saga_id = ? ORDER BY created_at`.

**Rejected alternatives:** choreography (see above), optimistic locking (retry thrashing), 2PC/XA (blocks resources, cascading failures), Temporal (extra infra; reconsider Phase 2+ if saga logic grows), CDC-driven saga state (circular).

---

## Events & Outbox (ADR-002)

**Publishing path:**
```
INSERT ledger + INSERT outbox   (same ACID transaction — both or neither)
  → Postgres WAL
  → Debezium (logical replication slot — Postgres PUSHES changes; ~1-2ms, not polling)
  → Kafka topic
  → consumers (account, notification, audit, reconciliation)
```

**Why events at all (vs consumers querying ledger):** loose coupling (schema evolution without redeploys), real-time (~1-2ms vs polling), zero read load on ledger, instant fan-out to any number of services, new consumers just subscribe.

**Topic design — critical rules:**
- **Single topic for outbox events** (`ledger.public.outbox`) — NEVER split event types across topics; order is guaranteed only within a partition, not across topics
- **Partition key = saga_id** — all events of one saga land in one partition, delivered in order; different sagas process in parallel
- **Repartitioning is safe** (atomic rebalance preserves per-partition order) as long as the partition key never changes

**Outbox schema:** `id, saga_id, event_type, payload JSONB, created_at, published_at, status` + index on `(status, created_at)`

**Delivery semantics:** at-least-once (Debezium may republish after crash) → deduplication is the CONSUMER's job (idempotent handlers, ADR-004/005 patterns)

**Housekeeping:** scheduled job deletes published outbox entries older than 7 days.

**Rejected alternatives:** dual-write (race between DB and broker), events-first (ghost events without ledger backing), in-memory queue (lost on crash), consumers polling ledger (latency + load + coupling).

---

## Idempotency (ADR-004)

**Scope note (updated — see other-docs/08's migration-numbering note; `ledger-service` isn't deployed yet, so this schema change is folded into its single `V1` migration, not a separate `V7`):** the chain below describes the
*original* client-facing pattern — where a real end-user's
`Idempotency-Key` deterministically derives `saga_id` — and it still
applies exactly as written to the future, not-yet-built
`transaction-service`'s saga-creation endpoint, the one place a client
ever actually mints an `Idempotency-Key`. `ledger-service` itself no
longer has a client-supplied `Idempotency-Key` at all: since ADR-003's
rate-lock expiry policy means every phase (`hold`/`lock`/`settle`/
`release`) happens at most once per saga, forever, `ledger`'s and
`fx_rate_locks`' constraints moved from `UNIQUE(..., idempotency_key,
...)` to `UNIQUE(user_id, saga_id[, entry_type])` — `(userId, sagaId,
phase)` is `ledger-service`'s whole write identity now. See
[other-docs/08](other-docs/08-ledger-service-design-decisions.md)
Decision 29.

**The chain (as `transaction-service`'s saga-creation endpoint will use it):**
```
Client generates Idempotency-Key (UUID v4, stored client-side, REUSED on retry)
        ↓
saga_id = UUID.nameUUIDFromBytes(user_id + "|" + idempotency_key)   ← DETERMINISTIC, never random
        ↓
UNIQUE(saga_id, idempotency_key, entry_type) on ledger               ← DB-level safety net
```

**Why each piece:**
- **Client-generated key**: only the client knows "this is the same attempt"; server just enforces
- **user_id in derivation** (from JWT `sub` claim — a UUID issued by auth-service): two users accidentally sending the same key derive DIFFERENT saga_ids and never block each other
- **Deterministic saga_id**: retry re-derives the SAME saga_id → constraint fires. A random saga_id would make every retry look new → silent duplicates (system-breaking bug)
- **Composite UNIQUE, not saga_id alone**: defense-in-depth against hash collisions — a collision with different keys stays allowed and detectable instead of blocking an innocent user (collision odds ~1 in 2^122: acceptable). This applies to EVERY table keyed on `saga_id`, not just `ledger` — found by inspection to be missing on `fx_rate_locks` (`UNIQUE(saga_id)` alone), fixed to `UNIQUE(saga_id, idempotency_key)` (later superseded by `UNIQUE(user_id, saga_id)`, Decision 29 — see other-docs/08's migration-numbering note for why these are no longer separate migration files); see [other-docs/08](other-docs/08-ledger-service-design-decisions.md) Decision 27
- **entry_type in the constraint**: one saga legitimately writes multiple legs (HOLD_DR + HOLD_CR share saga_id + key); a retry re-inserting the same leg violates → whole transaction rolls back (atomicity)

**Request-handling pattern (API layer):**
```
1. Cache check (Redis, key = user_id + "|" + idempotency_key) — fast path, performance ONLY
2. Try the insert — the DATABASE is the judge of duplicates
3. On UNIQUE violation: read the original from the DB
   (NOT the cache — first attempt may have crashed before cache.put),
   reconstruct the response, backfill the cache, return it
```

**TTLs:** cached responses and idempotency records kept 24h (covers retry window; nightly cleanup job).

**Three IDs — never confuse:**
| ID | Purpose | Uniqueness enforced? |
|---|---|---|
| Idempotency-Key | Prevent duplicate client requests | Yes (via constraint) |
| Saga-ID | Track saga progress internally | Yes (derived deterministically) |
| Correlation-ID | Trace one request across services (observability) | **No — never used for dedup; may be shared across related requests** |

**Security notes:** Idempotency-Key is not a secret (but travels over HTTPS); rate-limit by API key, not by Idempotency-Key; log every attempt (retries logged as cache/duplicate hits) for audit.

**Rejected alternatives:** Retry-After header (delays, doesn't dedupe), rollback-only (retries create new sagas), separate dedup service (complexity without gain), SELECT-then-INSERT check (race between check and insert — the constraint IS the atomic check).

---

## Read Side — CQRS (ADR-005)

**account-service is a pure projection: written ONLY by event consumption, never by request handlers.**

**Why:** reads outnumber writes ~100:1; `SUM` over an append-only ledger is slow and puts read traffic on the money-critical write path. Projection = <1ms point-reads, zero ledger load, independent scaling (service replicas + DB read replicas).

**Schema:**
```sql
account_balances (
  account_id, currency,             -- PK (multi-currency native)
  available_balance, held_balance,
  last_event_id,                    -- idempotent-apply guard
  updated_at
)
```

**Balance math per event:**
| Event | Effect |
|---|---|
| TransferHeld | `available -= x, held += x` |
| TransferSettled | source: `held -= x`; recipient: `available += converted` |
| TransferFailed | `held -= x, available += x` (money returned) |

**Idempotent apply (redelivery-safe):**
```sql
UPDATE account_balances SET ..., last_event_id = :id
WHERE account_id = :acc AND currency = :cur
  AND last_event_id < :id;   -- 0 rows affected = already applied → skip
```

**Rules:**
- **Eventual consistency (~10-50ms lag) is acceptable BY DESIGN** — the projection is display-only; the saga checks funds against the LEDGER, so a stale projection can never cause an overspend
- **Projection is disposable**: TRUNCATE + rebuild from ledger (or replay the topic from offset 0) anytime; a projection bug is an inconvenience, never data loss
- **Redis sits IN FRONT of the Postgres projection, never replaces it** — Redis restart = cold (RAM wiped); RDB snapshots lose minutes, AOF `everysec` still loses ~1s and `always` kills performance; no SQL for reconciliation
- **reconciliation-service** compares ledger SUM vs projection nightly (sampled hourly) — drift target ZERO; any drift = alert + auto-rebuild that account + root-cause
- **Monitor consumer lag**; alert if > 5s; scale consumers via partitions

**Rejected alternatives:** direct ledger queries (slow, couples read load to write path), synchronous balance updates inside the saga (hot-account lock contention + reintroduces dual-write), Postgres materialized views (full recompute, still on ledger DB), Redis-only store (durability).

---

## Concurrency Cheat Sheet

| Mechanism | Where used | What it prevents |
|---|---|---|
| SERIALIZABLE isolation | All ledger/saga writes | Dirty reads, phantom reads, anomalies |
| `SELECT ... FOR UPDATE SKIP LOCKED` | saga_state claiming | Two orchestrators processing the same saga — SKIP LOCKED also avoids one instance blocking/wasting time waiting on rows another already claimed |
| `FOR UPDATE SKIP LOCKED` + `ORDER BY` + `LIMIT n` | Orchestrator batching | Duplicate AND overlapping batch claims across instances |
| UNIQUE(user_id, saga_id, entry_type) | ledger (was `idempotency_key`-keyed pre-V7, other-docs/08 Decision 29) | Duplicate money movement on retry |
| Deterministic saga_id | Request → saga mapping | Retries creating "new" sagas |
| `last_event_id` guard | Read-model consumers | Double-applying redelivered events |
| Partition key = saga_id | Event topics | Out-of-order event processing per saga |

---

## Performance Targets

| Metric | Target |
|---|---|
| POST /v1/transfers P95 | < 1.2s |
| Ledger write | < 50ms |
| Saga execution | < 800ms |
| Balance read | < 1ms (projection point-read / cache) |
| Transfer success rate | > 99.5% |
| Duplicate rate | 0% |
| Recovery SLA | < 15 min |
| Projection drift | 0 (reconciliation-verified) |

---

## Known Open Gaps (tracked so nothing gets forgotten)

Real, identified gaps in the current build — documented deliberately instead
of silently deferred. Not exhaustive on their own; each links to its full
writeup.

| Gap | Where it belongs | Tracked in |
|---|---|---|
| No API-level rate limiting (per-user/per-IP throttling) anywhere in the system | `api-gateway` (not yet built) | [ADR-004](ADR-004-idempotency.md)'s Security Considerations |
| `hold` performs no balance validation — no service checks sufficient funds before writing | `account-service` (not yet built) | [other-docs/08](other-docs/08-ledger-service-design-decisions.md) Decision 23 |
| `auth-service` issuing service-identity tokens; dual-auth-shape acceptance beyond `ledger-service` | `auth-service` (not yet built) | [ADR-007](ADR-007-internal-service-authentication.md)'s open items |
| ~~Recovery/redo must reuse the SAME `Idempotency-Key` per (saga, phase)~~ **RESOLVED — became moot**, not by building this: `ledger-service` removed its client-supplied `Idempotency-Key` entirely before `saga-orchestrator` existed, so a redo is just calling the same phase again for the same `sagaId`. The equivalent discipline DOES exist for `fx-rate-service`'s `executionId` — see `SagaState`'s javadoc | `saga-orchestrator` (built) | [ADR-004](ADR-004-idempotency.md)'s Open Requirement section (updated), [other-docs/10](other-docs/10-saga-orchestrator-plan.md) |
| No real FX liquidity-provider integration — `MockFxProvider` is the only implementation | `fx-rate-service` | [other-docs/09](other-docs/09-fx-rate-service-design-decisions.md) |
| No auth wiring on `fx-rate-service` yet (deliberate — no real caller to authenticate) | `fx-rate-service` | [other-docs/09](other-docs/09-fx-rate-service-design-decisions.md) Decision 4 |
| `fx_executions` has `UNIQUE(execution_id)` alone, no `user_id` column — same saga_id-collision-blast-radius gap `ledger`/`fx_rate_locks` had before their own `user_id` fixes (other-docs/08 Decisions 29/31), plus `GET /v1/fx/executions/{id}` and `recoverFromDb` are both unscoped by caller. Blocked on `fx-rate-service` having no real `userId` concept yet (`SYSTEM_CALLER` placeholder) — found while building `saga-orchestrator`, whose derived `executionId` inherits this from `fx_executions` itself, not introduced by `saga-orchestrator` | `fx-rate-service` (needs real auth first) | [other-docs/10](other-docs/10-saga-orchestrator-design-decisions.md) Decision 6 |
| ~~No `expires_at`/TTL on the locked rate~~ **RESOLVED** — `fx-rate-service`'s `GET /v1/fx/quotes` issues it, `FxRateLock.expiresAt` stores it. Enforcement POLICY (release-only, no relock — ADR-003) is now BUILT: `saga-orchestrator`'s `executeTrade` checks `rateExpiresAt` and releases+fails rather than executing against an expired lock | `saga-orchestrator` (built) | [ADR-003](ADR-003-saga-orchestrator.md)'s rate-lock expiry section, [other-docs/08](other-docs/08-ledger-service-design-decisions.md) Decision 24, [other-docs/09](other-docs/09-fx-rate-service-design-decisions.md) Decision 6, [other-docs/10](other-docs/10-saga-orchestrator-plan.md) |
| Retry-as-new-saga (who decides, and how) for a saga that failed on an expired lock — deferred, not designed | `transaction-service` (not yet built) | [ADR-003](ADR-003-saga-orchestrator.md)'s rate-lock expiry section, [other-docs/10](other-docs/10-saga-orchestrator-plan.md)'s Deferred list |
