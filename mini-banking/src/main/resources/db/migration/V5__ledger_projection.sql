CREATE TABLE IF NOT EXISTS processed_events (
    consumer_group  VARCHAR(50) NOT NULL,
    event_id        UUID NOT NULL,
    processed_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (consumer_group, event_id)
);

CREATE TABLE IF NOT EXISTS ledger_entries (
    id              BIGSERIAL PRIMARY KEY,
    transaction_id  UUID NOT NULL,
    account_id      VARCHAR(50) NOT NULL,
    account_type    VARCHAR(30) NOT NULL CHECK (account_type IN ('USER_WALLET', 'SETTLEMENT', 'CLEARING')),
    entry_type      VARCHAR(10) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount          BIGINT NOT NULL CHECK (amount > 0),
    currency        VARCHAR(3) NOT NULL DEFAULT 'VND',
    occurred_at     TIMESTAMPTZ NOT NULL,
    event_id        UUID NOT NULL,
    CONSTRAINT uq_ledger_event_entry_type UNIQUE (event_id, entry_type)
);

CREATE INDEX IF NOT EXISTS idx_ledger_tx ON ledger_entries (transaction_id);
CREATE INDEX IF NOT EXISTS idx_ledger_account ON ledger_entries (account_id, occurred_at);
