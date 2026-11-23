-- ============================================================================
-- Mini-Banking DEV Cluster Bootstrap (Step 1 of 2)
-- Run in pgAdmin connected to the DEV Postgres server (port 5410), database: postgres
-- IMPORTANT: Replace REPLACE_WITH_STRONG_DEV_PASSWORD before executing!
-- ============================================================================

DO $$
DECLARE
    v_dev_password TEXT := 'REPLACE_WITH_STRONG_DEV_PASSWORD';
BEGIN
    IF v_dev_password LIKE 'REPLACE_WITH_STRONG_%' THEN
        RAISE EXCEPTION 'Refusing to run with placeholder password. Replace REPLACE_WITH_STRONG_DEV_PASSWORD first.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mb_dev') THEN
        EXECUTE format('CREATE ROLE mb_dev LOGIN PASSWORD %L', v_dev_password);
    ELSE
        EXECUTE format('ALTER ROLE mb_dev LOGIN PASSWORD %L', v_dev_password);
    END IF;
END
$$;

-- ============================================================================
-- Step 2 of 2 (run as a SINGLE standalone statement in pgAdmin — PG16 forbids
-- CREATE DATABASE inside a multi-statement transaction block):
--
--   CREATE DATABASE walletledger_dev OWNER mb_dev;
-- ============================================================================
