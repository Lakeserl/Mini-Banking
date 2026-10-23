package com.lakeserl.mini_banking.shared.eventsourcing;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManagerFactory;

/**
 * Enforces ADR-001 ("never update/delete events") in the database: any UPDATE, DELETE or TRUNCATE on
 * {@code events} raises an error. Hibernate's ddl-auto cannot create triggers, so they are installed here,
 * idempotently, after Hibernate has created the table (hence the EntityManagerFactory dependency).
 *
 * <p>Production hardening on top of this: the application's DB role should not own the table and should
 * only have INSERT and SELECT on it, so it also cannot drop the trigger.
 */
@Component
public class EventStoreImmutabilityGuard implements InitializingBean {

	private final JdbcTemplate jdbc;

	public EventStoreImmutabilityGuard(JdbcTemplate jdbc, EntityManagerFactory entityManagerFactory) {
		this.jdbc = jdbc;
	}

	@Override
	public void afterPropertiesSet() {
		jdbc.execute("""
				CREATE OR REPLACE FUNCTION events_reject_mutation() RETURNS trigger
				LANGUAGE plpgsql AS $$
				BEGIN
				    RAISE EXCEPTION 'events is append-only: % is not allowed', TG_OP
				        USING ERRCODE = 'integrity_constraint_violation';
				END;
				$$
				""");
		jdbc.execute("DROP TRIGGER IF EXISTS trg_events_no_update_delete ON events");
		jdbc.execute("""
				CREATE TRIGGER trg_events_no_update_delete BEFORE UPDATE OR DELETE ON events
				FOR EACH ROW EXECUTE FUNCTION events_reject_mutation()
				""");
		jdbc.execute("DROP TRIGGER IF EXISTS trg_events_no_truncate ON events");
		jdbc.execute("""
				CREATE TRIGGER trg_events_no_truncate BEFORE TRUNCATE ON events
				FOR EACH STATEMENT EXECUTE FUNCTION events_reject_mutation()
				""");
	}
}
