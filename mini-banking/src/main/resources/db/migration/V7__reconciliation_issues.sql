CREATE TABLE IF NOT EXISTS reconciliation_issues (
    id                BIGSERIAL PRIMARY KEY,
    issue_type        VARCHAR(50) NOT NULL
                          CHECK (issue_type IN ('INVARIANT_VIOLATION', 'CLEARING_NON_ZERO', 'BALANCE_MISMATCH', 'BANK_MISMATCH')),
    dedupe_key        VARCHAR(128) NOT NULL,
    details           JSONB NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'OPEN'
                          CHECK (status IN ('OPEN', 'RESOLVED')),
    detected_at       TIMESTAMPTZ NOT NULL,
    resolved_at       TIMESTAMPTZ,
    resolved_by       VARCHAR(100),
    resolution_notes  TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_recon_open_dedupe
    ON reconciliation_issues (dedupe_key) WHERE status = 'OPEN';

CREATE INDEX IF NOT EXISTS idx_recon_status_detected
    ON reconciliation_issues (status, detected_at DESC, id DESC);
