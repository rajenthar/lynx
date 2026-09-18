-- account-service's own tables. Single V1 file — not
-- deployed yet.

-- One account per (user, currency) — a user can hold as many DIFFERENT
-- currencies as they like (SGD + USD + EUR, no limit), just not two
-- accounts in the SAME currency. A deliberate decision, not a default:
-- the alternative (letting a user open multiple accounts in one currency,
-- e.g. separate "savings"/"spending" SGD accounts) was considered and
-- rejected for now — simpler mental model, closer to how a typical
-- consumer wallet works. Revisit if that use case is ever actually asked for.
CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    available NUMERIC(19,4) NOT NULL,
    held NUMERIC(19,4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (user_id, currency)
);

-- No separate index on user_id alone needed for listAccounts(userId) —
-- the UNIQUE(user_id, currency) constraint above already IS a composite
-- btree index whose leading column (user_id) Postgres can use directly.

-- The CQRS projection's own idempotency guard — the "Idempotent Consumer"
-- pattern (https://microservices.io/patterns/communication-style/idempotent-consumer.html),
-- not a single global high-water-mark. A high-water-mark
-- ("skip if eventId <= last seen") silently assumes eventIds arrive in
-- strictly increasing order — true within ONE Kafka partition, NOT
-- guaranteed across MULTIPLE partitions of the same topic, where a
-- lower eventId can genuinely arrive after a higher one with no relation
-- between them. This table has no such assumption: each event's own id
-- is inserted exactly once (`INSERT ... ON CONFLICT DO NOTHING`), correct
-- regardless of delivery order or how many partitions the topic has.
-- Safe here specifically because every one of EventProjector's balance
-- adjustments is an unconditional additive delta (`available += x`) —
-- commutative, so "applied exactly once" is the only guarantee actually
-- needed; order was never a real correctness requirement for THIS
-- projection. `processed_at` exists for future pruning (delete rows
-- older than the topic's own retention.ms — Kafka can never redeliver
-- anything older than that anyway), not built yet.
CREATE TABLE processed_events (
    event_id BIGINT PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A user's own address book for transfers — "save this recipient as 'Bob'"
-- so future transfers don't require retyping a raw userId. Deliberately a
-- real, server-side, per-user table (NOT client-side localStorage, which
-- was tried first and rejected: it doesn't survive a new device/browser,
-- doesn't sync across sessions, and is scoped to the browser origin
-- rather than to which Lynx account is actually logged in).
--
-- Two UNIQUE constraints enforce the two collision rules this feature
-- needs, AT THE DATABASE LEVEL (not just in application code, which is
-- itself checked first for a friendlier error, same pattern as
-- accounts' own UNIQUE(user_id, currency)):
--   1. (user_id, recipient_user_id) — the SAME recipient can only be
--      saved once per user; saving them again is an UPDATE (new label),
--      never a second row.
--   2. (user_id, lower(label)) — a functional unique index, so two
--      DIFFERENT recipients can never share a label (case-insensitively)
--      for the SAME user — labels must stay unambiguous.
CREATE TABLE saved_recipients (
    id UUID PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    recipient_user_id VARCHAR(255) NOT NULL,
    label VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (user_id, recipient_user_id)
);

CREATE UNIQUE INDEX idx_saved_recipients_user_label ON saved_recipients (user_id, lower(label));
