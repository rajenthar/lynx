-- other-docs/11: auth-service's own tables. Single V1 file — this service
-- isn't deployed yet, so there's no released schema to migrate incrementally
-- from (see other-docs/08's migration-numbering note).

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Who may request a service-identity token via the Client Credentials grant
-- (ADR-007 Option C; other-docs/11 Decision 3). The secret is stored only as
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
