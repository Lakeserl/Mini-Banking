ALTER TABLE events ADD COLUMN IF NOT EXISTS global_seq BIGSERIAL NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_events_global_seq ON events (global_seq);
CREATE INDEX IF NOT EXISTS idx_events_occurred_at ON events (occurred_at);

ALTER TABLE outbox ADD COLUMN IF NOT EXISTS aggregate_version BIGINT;

CREATE TABLE IF NOT EXISTS wallet_balances (
    wallet_id          UUID PRIMARY KEY,
    owner_id           UUID NOT NULL,
    currency           VARCHAR(3) NOT NULL DEFAULT 'VND',
    balance            BIGINT NOT NULL CHECK (balance >= 0),
    status             VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'LOCKED')),
    aggregate_version  BIGINT NOT NULL CHECK (aggregate_version >= 1),
    updated_at         TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_wallet_balances_owner ON wallet_balances (owner_id);

CREATE TABLE IF NOT EXISTS transaction_history (
    id                     BIGSERIAL PRIMARY KEY,
    transaction_id         UUID NOT NULL,
    wallet_id              UUID NOT NULL,
    tx_type                VARCHAR(20) NOT NULL CHECK (tx_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'REFUND')),
    direction              VARCHAR(10) NOT NULL CHECK (direction IN ('IN', 'OUT')),
    amount                 BIGINT NOT NULL CHECK (amount > 0),
    currency               VARCHAR(3) NOT NULL DEFAULT 'VND',
    status                 VARCHAR(20) NOT NULL,
    counterpart_wallet_id  UUID,
    counterpart_label      VARCHAR(100),
    bank_reference         VARCHAR(100),
    original_transfer_id   UUID,
    failure_reason         TEXT,
    occurred_at            TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_tx_history_tx_wallet UNIQUE (transaction_id, wallet_id)
);

CREATE INDEX IF NOT EXISTS idx_tx_history_wallet_cursor
    ON transaction_history (wallet_id, occurred_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_tx_history_tx_id
    ON transaction_history (transaction_id);

CREATE TABLE IF NOT EXISTS daily_reports (
    report_date       DATE NOT NULL,
    currency          VARCHAR(3) NOT NULL DEFAULT 'VND',
    total_deposit     BIGINT NOT NULL DEFAULT 0 CHECK (total_deposit >= 0),
    total_withdrawal  BIGINT NOT NULL DEFAULT 0 CHECK (total_withdrawal >= 0),
    total_transfer    BIGINT NOT NULL DEFAULT 0 CHECK (total_transfer >= 0),
    total_refund      BIGINT NOT NULL DEFAULT 0 CHECK (total_refund >= 0),
    tx_count          BIGINT NOT NULL DEFAULT 0 CHECK (tx_count >= 0),
    updated_at        TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (report_date, currency)
);
