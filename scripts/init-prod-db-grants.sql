-- ============================================================================
-- Mini-Banking PROD Database Grants (Step 3 of 3)
-- MUST be executed while connected directly to database `walletledger_prod`
-- BEFORE the first Flyway migration run.
-- ============================================================================

DO $$
BEGIN
    IF current_database() <> 'walletledger_prod' THEN
        RAISE EXCEPTION 'Wrong database "%". Connect to walletledger_prod before running init-prod-db-grants.sql.', current_database();
    END IF;
END
$$;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO mb_prod_migrator;
GRANT USAGE ON SCHEMA public TO mb_prod_app;
GRANT CONNECT ON DATABASE walletledger_prod TO mb_prod_migrator, mb_prod_app;

-- Default privileges for all future tables/sequences created by Flyway (mb_prod_migrator)
ALTER DEFAULT PRIVILEGES FOR ROLE mb_prod_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO mb_prod_app;

ALTER DEFAULT PRIVILEGES FOR ROLE mb_prod_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO mb_prod_app;

-- Idempotent grants if re-run after tables already exist
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO mb_prod_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO mb_prod_app;

-- Keep Flyway metadata table off-limits to app role if this script is re-run post-migration
DO $$
BEGIN
    IF to_regclass('public.flyway_schema_history') IS NOT NULL THEN
        EXECUTE 'REVOKE ALL ON TABLE public.flyway_schema_history FROM mb_prod_app';
    END IF;
END
$$;
