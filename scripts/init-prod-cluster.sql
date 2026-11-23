-- ============================================================================
-- Mini-Banking PROD Cluster Bootstrap (Step 1 of 3)
-- Run in pgAdmin connected to the PROD Postgres server (port 5433), database: postgres
-- IMPORTANT: Replace BOTH placeholders below with strong unique passwords matching
-- GitLab CI/CD Variables (PROD_DB_MIGRATION_PASSWORD and PROD_DB_PASSWORD)!
-- ============================================================================

DO $$
DECLARE
    v_migrator_password TEXT := 'REPLACE_WITH_STRONG_PROD_MIGRATOR_PASSWORD';
    v_app_password      TEXT := 'REPLACE_WITH_STRONG_PROD_APP_PASSWORD';
BEGIN
    IF v_migrator_password LIKE 'REPLACE_WITH_STRONG_%' OR v_app_password LIKE 'REPLACE_WITH_STRONG_%' THEN
        RAISE EXCEPTION 'Refusing to run with placeholder passwords. Replace both passwords first.';
    END IF;

    IF v_migrator_password = v_app_password THEN
        RAISE EXCEPTION 'mb_prod_migrator and mb_prod_app must use distinct passwords.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mb_prod_migrator') THEN
        EXECUTE format('CREATE ROLE mb_prod_migrator LOGIN PASSWORD %L', v_migrator_password);
    ELSE
        EXECUTE format('ALTER ROLE mb_prod_migrator LOGIN PASSWORD %L', v_migrator_password);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mb_prod_app') THEN
        EXECUTE format('CREATE ROLE mb_prod_app LOGIN PASSWORD %L', v_app_password);
    ELSE
        EXECUTE format('ALTER ROLE mb_prod_app LOGIN PASSWORD %L', v_app_password);
    END IF;
END
$$;

-- ============================================================================
-- Step 2 of 3 (run as a SINGLE standalone statement in pgAdmin — PG16 forbids
-- CREATE DATABASE inside a multi-statement transaction block):
--
--   CREATE DATABASE walletledger_prod OWNER mb_prod_migrator;
--
-- Then open a NEW pgAdmin Query Tool connected directly to database
-- `walletledger_prod` and execute `scripts/init-prod-db-grants.sql` (Step 3 of 3).
-- ============================================================================
