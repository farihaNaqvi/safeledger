-- What the customer typed, recorded by agent-service through an internal endpoint (never by the
-- model). Write tools check that a transfer's or dispute's amount and payee or merchant, and an
-- unfreeze's card, appear in these words and not only in tool output (data-flow control).
CREATE TABLE customer_utterance (
    id          BIGSERIAL PRIMARY KEY,
    customer_id UUID          NOT NULL,
    session_id  VARCHAR(100)  NOT NULL,
    text        VARCHAR(2000) NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX customer_utterance_session_idx ON customer_utterance (customer_id, session_id);
