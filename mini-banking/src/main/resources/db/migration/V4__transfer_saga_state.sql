CREATE TABLE IF NOT EXISTS transfer_saga_state (
    transfer_id           UUID PRIMARY KEY,
    source_wallet_id      UUID NOT NULL,
    dest_wallet_id        UUID NOT NULL,
    amount                BIGINT NOT NULL CHECK (amount > 0),
    currency              VARCHAR(3) NOT NULL DEFAULT 'VND',
    transfer_type         VARCHAR(20) NOT NULL CHECK (transfer_type IN ('STANDARD', 'REFUND')),
    original_transfer_id  UUID,
    status                VARCHAR(20) NOT NULL CHECK (status IN ('INITIATED', 'DEBITED', 'COMPLETED', 'FAILED', 'REVERSING', 'REVERSED')),
    retry_count           INT NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    failure_reason        TEXT,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_transfer_saga_stuck
    ON transfer_saga_state (status, updated_at)
    WHERE status IN ('INITIATED', 'DEBITED', 'REVERSING');

CREATE INDEX IF NOT EXISTS idx_transfer_saga_source_created
    ON transfer_saga_state (source_wallet_id, created_at);

CREATE UNIQUE INDEX IF NOT EXISTS uq_transfer_active_refund
    ON transfer_saga_state (original_transfer_id)
    WHERE original_transfer_id IS NOT NULL AND status NOT IN ('FAILED', 'REVERSED');
