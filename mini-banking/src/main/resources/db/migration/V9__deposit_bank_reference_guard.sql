CREATE TABLE IF NOT EXISTS deposit_bank_references (
    deposit_id     UUID PRIMARY KEY,
    bank_reference TEXT NOT NULL,
    wallet_id      UUID NOT NULL,
    amount         BIGINT NOT NULL CHECK (amount > 0),
    currency       VARCHAR(3) NOT NULL DEFAULT 'VND',
    created_at     TIMESTAMPTZ NOT NULL
);

-- Fail fast if historical DepositRequested events contain oversized or duplicate (case-insensitive) bankReference values
DO $$
DECLARE
    oversized_ids TEXT;
    duplicate_summary TEXT;
BEGIN
    SELECT string_agg(aggregate_id::text, ', ' ORDER BY aggregate_id)
    INTO oversized_ids
    FROM events
    WHERE aggregate_type = 'Deposit'
      AND event_type = 'DepositRequested'
      AND octet_length(payload->>'bankReference') > 2000;

    IF oversized_ids IS NOT NULL THEN
        RAISE EXCEPTION 'V9 migration blocked: DepositRequested bankReference exceeds 2000 bytes for deposit_id(s): %', oversized_ids;
    END IF;

    SELECT string_agg(
               upper_ref || ' => [' || deposit_ids || ']',
               '; '
               ORDER BY upper_ref
           )
    INTO duplicate_summary
    FROM (
        SELECT UPPER(payload->>'bankReference') AS upper_ref,
               string_agg(DISTINCT aggregate_id::text, ', ' ORDER BY aggregate_id::text) AS deposit_ids
        FROM events
        WHERE aggregate_type = 'Deposit'
          AND event_type = 'DepositRequested'
          AND payload->>'bankReference' IS NOT NULL
        GROUP BY UPPER(payload->>'bankReference')
        HAVING COUNT(DISTINCT aggregate_id) > 1
    ) dups;

    IF duplicate_summary IS NOT NULL THEN
        RAISE EXCEPTION 'V9 migration blocked: duplicate deposit bank_reference(s) found in events: %', duplicate_summary;
    END IF;
END $$;

INSERT INTO deposit_bank_references (
    deposit_id, bank_reference, wallet_id, amount, currency, created_at
)
SELECT
    aggregate_id AS deposit_id,
    payload->>'bankReference' AS bank_reference,
    (payload->>'walletId')::uuid AS wallet_id,
    (payload->'amount'->>'amount')::bigint AS amount,
    COALESCE(payload->'amount'->>'currency', 'VND') AS currency,
    occurred_at AS created_at
FROM events
WHERE aggregate_type = 'Deposit'
  AND event_type = 'DepositRequested'
  AND payload->>'bankReference' IS NOT NULL
  AND payload->>'walletId' IS NOT NULL
  AND (payload->'amount'->>'amount')::bigint > 0
ON CONFLICT (deposit_id) DO NOTHING;

CREATE UNIQUE INDEX IF NOT EXISTS uq_deposit_bank_reference_upper
    ON deposit_bank_references (UPPER(bank_reference));

-- Keep brief ACCESS EXCLUSIVE metadata locks at the end of the migration transaction
ALTER TABLE transaction_history
    ALTER COLUMN bank_reference TYPE TEXT;

ALTER TABLE idempotency_keys
    ADD COLUMN IF NOT EXISTS lease_token UUID;

DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    FOR constraint_name IN
        SELECT c.conname
        FROM pg_constraint c
        WHERE c.conrelid = 'idempotency_keys'::regclass
          AND c.contype = 'c'
          AND pg_get_constraintdef(c.oid) LIKE '%status%'
    LOOP
        EXECUTE format('ALTER TABLE idempotency_keys DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;

ALTER TABLE idempotency_keys
    ADD CONSTRAINT chk_idempotency_keys_status
    CHECK (status IN ('IN_PROGRESS', 'PENDING', 'RETRYING', 'COMPLETED'));
