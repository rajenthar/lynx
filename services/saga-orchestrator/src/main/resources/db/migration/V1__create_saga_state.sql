-- Not deployed yet — single starting schema, same convention as
-- ledger-service's own V1 (see other-docs/08's migration-numbering note).
-- Once this service is actually deployed, schema changes go back to
-- incremental Vn migrations instead of editing V1 directly.
--
-- saga_state: one row per saga, MUTATED repeatedly across its lifetime
-- (status advances HOLDING -> LOCKED -> EXECUTED -> SETTLED, or diverts to
-- FAILED) — unlike ledger-service's own append-only tables. ADR-003's
-- polling loop claims batches via FOR UPDATE SKIP LOCKED against the two
-- indexes below; see SagaStateRepository for the exact queries.
--
-- Surrogate `id` primary key, NOT saga_id (found by direct inspection,
-- other-docs/10 Decision 7) — saga_id is deterministically derived
-- upstream (a future transaction-service, from a real client's own
-- Idempotency-Key), so it can theoretically collide between two unrelated
-- users, same reasoning as ledger/fx_rate_locks (other-docs/08 Decisions
-- 29/31). Worse here than in those tables: with saga_id as a plain
-- single-column PK, two different users could never even coexist as
-- separate rows on a collision. UNIQUE(user_id, saga_id) below turns a
-- collision into a harmless, distinguishable extra row instead of one
-- caller's saga silently misrouting to another user's.
CREATE TABLE saga_state (
  id BIGSERIAL PRIMARY KEY,
  saga_id UUID NOT NULL,
  user_id VARCHAR(255) NOT NULL,
  status VARCHAR(20) NOT NULL,           -- HOLDING | LOCKED | EXECUTED | SETTLED | FAILED
  from_account_id UUID NOT NULL,
  to_account_id UUID NOT NULL,
  amount NUMERIC(19,4) NOT NULL,
  from_currency VARCHAR(3) NOT NULL,
  to_currency VARCHAR(3) NOT NULL,
  rate NUMERIC(19,8),                    -- set once LOCKED (fx-rate-service's quoted rate)
  rate_expires_at TIMESTAMPTZ,           -- set once LOCKED
  execution_id UUID,                     -- deterministic (ExecutionIds), set once LOCKED — fx-rate-service's own idempotency key; reused verbatim on every redo
  filled_rate NUMERIC(19,8),             -- set once EXECUTED — what SETTLE actually credits with, not the original quoted rate
  failure_reason VARCHAR(500),           -- set only on FAILED
  retried_from_saga_id UUID,             -- audit-only (other-docs/10 Decision 2) — NOT a uniqueness-bearing key, no contention on it
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  version BIGINT NOT NULL DEFAULT 0,     -- JPA @Version — belt-and-braces under the SKIP LOCKED claim, see SagaState's javadoc
  UNIQUE (user_id, saga_id)
);

-- Orchestrator polling: one query per status (never combined), ORDER BY +
-- LIMIT + FOR UPDATE SKIP LOCKED against this index.
CREATE INDEX idx_saga_state_status_created ON saga_state (status, created_at);

-- Recovery worker: finds sagas stuck at a non-terminal status whose
-- updated_at is stale (see SagaStateRepository.claimStale).
CREATE INDEX idx_saga_state_status_updated ON saga_state (status, updated_at);
