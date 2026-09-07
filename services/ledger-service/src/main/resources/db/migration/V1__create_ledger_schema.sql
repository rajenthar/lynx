-- Not deployed yet — consolidated from what was previously V1-V7 into a
-- single starting schema. Once this is deployed anywhere, further changes
-- go back to incremental Vn migrations instead of editing this file.
--
-- ledger: one row per money-movement leg (double-entry, append-only —
-- ADR-001). UNIQUE(user_id, saga_id, entry_type) is the write-identity for
-- every phase (hold/lock/settle/release): with ADR-003's rate-lock expiry
-- policy (an expired LOCK is always released, never re-locked within the
-- SAME saga — a retry gets a brand-new sagaId instead), each phase happens
-- AT MOST ONCE per saga, forever, so (userId, sagaId, entry_type) alone is
-- sufficient — no separate client-supplied idempotency key is needed (see
-- other-docs/08 Decision 29). user_id also closes a saga_id-collision gap:
-- saga_id is deterministically derived (userId + a client Idempotency-Key,
-- upstream of this service — see ADR-004), so it can't be mathematically
-- guaranteed collision-free; the composite constraint means a collision
-- between two unrelated sagas is a harmless, detectable extra row instead
-- of a false-positive "duplicate" that corrupts an unrelated transaction.
CREATE TABLE ledger (
  id BIGSERIAL PRIMARY KEY,
  saga_id UUID NOT NULL,
  user_id VARCHAR(255) NOT NULL,
  entry_type VARCHAR(20) NOT NULL,
  account_id UUID NOT NULL,
  amount NUMERIC(19,4) NOT NULL,
  currency VARCHAR(3) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, saga_id, entry_type)
);

CREATE INDEX idx_ledger_saga_id ON ledger (saga_id, created_at);

-- outbox: transactional-outbox pattern (ADR-002) — every domain event is
-- written in the SAME transaction as its ledger legs, then relayed to
-- Kafka via CDC (Debezium), never published directly by this service.
-- user_id is a PLAIN column here, not part of any UNIQUE constraint —
-- outbox isn't itself idempotency-protected (the ledger insert above
-- always runs first in the same transaction and is what actually rejects
-- a retry). It exists purely so a saga_id collision between two different
-- users (other-docs/08 Decision 29) still leaves their outbox rows
-- individually filterable, instead of indistinguishable except by
-- decoding each row's JSON payload by hand.
CREATE TABLE outbox (
  id BIGSERIAL PRIMARY KEY,
  saga_id UUID NOT NULL,
  user_id VARCHAR(255) NOT NULL,
  event_type VARCHAR(30) NOT NULL,
  payload JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at TIMESTAMPTZ,
  status VARCHAR(10) NOT NULL DEFAULT 'PENDING'
);

CREATE INDEX idx_outbox_status_created ON outbox (status, created_at);

-- fx_rate_locks: one row per saga's LOCK phase — the FX rate a saga
-- committed to, plus that quote's own expiry (other-docs/09 Decision 6).
-- Deliberately its own table, not columns on the ledger's LOCK_DR/LOCK_CR
-- legs: it's one fact per saga, not one fact per leg, and a commitment
-- with its own lifecycle rather than a plain money-movement record.
-- ledger-service stores/exposes expires_at but never enforces it itself
-- (pure command-executor principle) — deciding what to do with an expired
-- lock is saga-orchestrator's job. UNIQUE(user_id, saga_id) mirrors
-- ledger's own composite-key collision defense, same reasoning as above.
CREATE TABLE fx_rate_locks (
  id BIGSERIAL PRIMARY KEY,
  saga_id UUID NOT NULL,
  user_id VARCHAR(255) NOT NULL,
  rate NUMERIC(19,8) NOT NULL,
  from_currency VARCHAR(3) NOT NULL,
  to_currency VARCHAR(3) NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (user_id, saga_id)
);
