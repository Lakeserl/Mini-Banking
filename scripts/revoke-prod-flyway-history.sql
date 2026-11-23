-- ============================================================================
-- Optional standalone hardening script (also executed idempotently by scripts/ci-migrate.sh prod)
-- Connect to `walletledger_prod` as `mb_prod_migrator` or superuser after initial Flyway run.
-- ============================================================================

DO $$
BEGIN
    IF current_database() <> 'walletledger_prod' THEN
        RAISE EXCEPTION 'Wrong database "%". Connect to walletledger_prod first.', current_database();
    END IF;
END
$$;

REVOKE ALL ON TABLE public.flyway_schema_history FROM mb_prod_app;
