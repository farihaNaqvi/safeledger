-- Bank schema for mock-bank-api. All money is NUMERIC(19,2) in INR. No full card numbers or full
-- account numbers are stored anywhere: cards keep last4 only, payees keep a masked account.

CREATE TABLE customer (
    id           UUID PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    email        VARCHAR(200) NOT NULL UNIQUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE account (
    id          UUID PRIMARY KEY,
    customer_id UUID          NOT NULL REFERENCES customer (id),
    type        VARCHAR(20)   NOT NULL CHECK (type IN ('SAVINGS', 'CURRENT')),
    currency    VARCHAR(3)    NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    balance     NUMERIC(19, 2) NOT NULL CHECK (balance >= 0)
);
CREATE INDEX account_customer_idx ON account (customer_id);

CREATE TABLE card (
    id            UUID PRIMARY KEY,
    customer_id   UUID           NOT NULL REFERENCES customer (id),
    account_id    UUID           NOT NULL REFERENCES account (id),
    last4         VARCHAR(4)     NOT NULL CHECK (last4 ~ '^[0-9]{4}$'),
    network       VARCHAR(20)    NOT NULL,
    status        VARCHAR(20)    NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN', 'BLOCKED')),
    monthly_limit NUMERIC(19, 2) NOT NULL CHECK (monthly_limit >= 0)
);
CREATE INDEX card_customer_idx ON card (customer_id);

-- Named bank_transaction because TRANSACTION is a reserved word in SQL.
CREATE TABLE bank_transaction (
    id          UUID PRIMARY KEY,
    account_id  UUID           NOT NULL REFERENCES account (id),
    card_id     UUID           REFERENCES card (id),
    amount      NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    direction   VARCHAR(10)    NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    merchant    VARCHAR(200)   NOT NULL,
    description VARCHAR(500)   NOT NULL,
    category    VARCHAR(50)    NOT NULL,
    posted_at   TIMESTAMPTZ    NOT NULL
);
CREATE INDEX bank_transaction_account_posted_idx ON bank_transaction (account_id, posted_at DESC);

CREATE TABLE payee (
    id             UUID PRIMARY KEY,
    customer_id    UUID         NOT NULL REFERENCES customer (id),
    name           VARCHAR(200) NOT NULL,
    masked_account VARCHAR(20)  NOT NULL,
    ifsc           VARCHAR(11)  NOT NULL,
    added_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX payee_customer_idx ON payee (customer_id);

-- Idempotency keys are unique per customer, not globally, so one customer can never replay
-- (and read back) another customer's transfer or dispute by guessing or reusing a key.
CREATE TABLE transfer (
    id              UUID PRIMARY KEY,
    customer_id     UUID           NOT NULL REFERENCES customer (id),
    from_account_id UUID           NOT NULL REFERENCES account (id),
    payee_id        UUID           NOT NULL REFERENCES payee (id),
    amount          NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    note            VARCHAR(200),
    status          VARCHAR(20)    NOT NULL CHECK (status IN ('COMPLETED')),
    idempotency_key VARCHAR(100)   NOT NULL,
    request_hash    VARCHAR(64)    NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT transfer_idempotency_uk UNIQUE (customer_id, idempotency_key)
);

CREATE TABLE dispute (
    id              UUID PRIMARY KEY,
    customer_id     UUID         NOT NULL REFERENCES customer (id),
    transaction_id  UUID         NOT NULL REFERENCES bank_transaction (id),
    reason          VARCHAR(500) NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('OPEN', 'RESOLVED', 'REJECTED')),
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT dispute_idempotency_uk UNIQUE (customer_id, idempotency_key)
);
CREATE INDEX dispute_customer_idx ON dispute (customer_id);
-- At most one open dispute per transaction, enforced by the database so concurrent requests
-- with different idempotency keys cannot both succeed.
CREATE UNIQUE INDEX dispute_one_open_per_txn_uk ON dispute (transaction_id) WHERE status = 'OPEN';
