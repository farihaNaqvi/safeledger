-- MCP server state, in its own database (safeledger_mcp), separate from the bank data so the
-- audit trail cannot be changed through the bank API's connection.

-- A write the model asked for. Nothing happens in the bank until the customer confirms it through
-- the internal confirm endpoint, which no MCP tool can reach.
CREATE TABLE pending_action (
    id              UUID PRIMARY KEY,
    customer_id     UUID           NOT NULL,
    session_id      VARCHAR(100)   NOT NULL,
    tool_name       VARCHAR(50)    NOT NULL,
    arguments       JSONB          NOT NULL,
    summary         VARCHAR(500)   NOT NULL,
    status          VARCHAR(20)    NOT NULL
        CHECK (status IN ('PENDING', 'CONFIRMED', 'EXECUTED', 'FAILED', 'EXPIRED', 'CANCELLED')),
    -- Sent to the bank on execution, so a retry after a lost response cannot run twice.
    idempotency_key VARCHAR(100)   NOT NULL UNIQUE,
    -- Transfer amount, kept as a column so daily limits can be summed in SQL. NULL for others.
    amount          NUMERIC(19, 2),
    created_at      TIMESTAMPTZ    NOT NULL,
    expires_at      TIMESTAMPTZ    NOT NULL,
    confirmed_at    TIMESTAMPTZ,
    executed_at     TIMESTAMPTZ,
    result          JSONB
);
CREATE INDEX pending_action_customer_status_idx ON pending_action (customer_id, status);
CREATE INDEX pending_action_expiry_idx ON pending_action (expires_at) WHERE status = 'PENDING';

-- Append-only audit trail: every tool call, rejected identity, and pending action transition.
CREATE TABLE audit_event (
    id             BIGSERIAL PRIMARY KEY,
    occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    event_type     VARCHAR(30)  NOT NULL,
    session_id     VARCHAR(100),
    customer_id    UUID,
    tool_name      VARCHAR(50),
    action_id      UUID,
    arguments      JSONB,
    outcome        VARCHAR(40)  NOT NULL,
    detail         VARCHAR(500),
    latency_ms     INTEGER,
    correlation_id VARCHAR(64)
);
CREATE INDEX audit_event_session_idx ON audit_event (session_id, occurred_at);
CREATE INDEX audit_event_action_idx ON audit_event (action_id);
