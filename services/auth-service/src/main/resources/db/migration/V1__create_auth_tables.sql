-- auth-service's own tables. Single V1 file — this service
-- isn't deployed yet, so there's no released schema to migrate incrementally
-- from.

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- One row per OTP sent. A new registration/resend inserts a fresh row rather
-- than updating one in place, so a user who mistypes an old code doesn't
-- accidentally get validated against a stale row still lying around.
CREATE TABLE email_otps (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    code TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_email_otps_user_id ON email_otps(user_id);

-- Who may request a service-identity token via the Client Credentials grant
-- (ADR-007 Option C). The secret is stored only as
-- a BCrypt hash, same as users.password_hash above — never in plaintext.
-- The one known caller today (saga-orchestrator) is seeded at application
-- startup instead of here, since a BCrypt hash is salted/non-deterministic
-- and there's no single fixed value to write into this file (see
-- ServiceClientSeeder's javadoc).
CREATE TABLE service_clients (
    client_id TEXT PRIMARY KEY,
    client_secret_hash TEXT NOT NULL,
    roles TEXT[] NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
