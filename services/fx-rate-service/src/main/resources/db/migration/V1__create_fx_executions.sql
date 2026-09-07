CREATE TABLE fx_executions (
  id BIGSERIAL PRIMARY KEY,
  execution_id UUID NOT NULL UNIQUE,
  saga_id UUID NOT NULL,
  amount NUMERIC(19,4) NOT NULL,
  from_currency VARCHAR(3) NOT NULL,
  to_currency VARCHAR(3) NOT NULL,
  requested_rate NUMERIC(19,8) NOT NULL,
  status VARCHAR(10) NOT NULL,          -- EXECUTED | FAILED
  filled_rate NUMERIC(19,8),            -- set only when status = EXECUTED
  failure_reason VARCHAR(500),          -- set only when status = FAILED
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_fx_executions_saga_id ON fx_executions (saga_id);
