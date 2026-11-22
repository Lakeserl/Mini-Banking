CREATE INDEX IF NOT EXISTS idx_tx_history_settled_bank
    ON transaction_history (updated_at, id)
    WHERE tx_type IN ('DEPOSIT', 'WITHDRAWAL') AND status = 'CONFIRMED';

CREATE INDEX IF NOT EXISTS idx_tx_history_pending_deposit
    ON transaction_history (occurred_at, id)
    WHERE tx_type = 'DEPOSIT' AND status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_idempotency_keys_expires_at
    ON idempotency_keys (expires_at);
