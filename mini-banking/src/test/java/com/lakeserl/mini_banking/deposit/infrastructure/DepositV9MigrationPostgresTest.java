package com.lakeserl.mini_banking.deposit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.deposit.domain.events.DepositConfirmed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositFailed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositRequested;
import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.EventSerializer;

@PostgresDataTest
class DepositV9MigrationPostgresTest {

	@Autowired
	DataSource dataSource;

	@Autowired
	EventSerializer serializer;

	@Test
	void cleanBackfillPopulatesClaimsWidensBankReferenceColumnAndAddsIdempotencyLeaseToken() throws Exception {
		String schema = "v9_clean_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		createSchema(schema);
		try {
			migrateSchema(schema, "8");

			UUID walletId = UUID.randomUUID();
			UUID depConfirmed = UUID.randomUUID();
			UUID depFailed = UUID.randomUUID();
			UUID depLong = UUID.randomUUID();
			String longRef = "LEGACY-" + "X".repeat(143);

			OffsetDateTime t1 = OffsetDateTime.of(2026, 11, 20, 1, 0, 0, 0, ZoneOffset.UTC);
			insertEvent(schema, depConfirmed, 1, new DepositRequested(depConfirmed, walletId, Money.vnd(500_000), "REF-CLEAN-1"), t1);
			insertEvent(schema, depConfirmed, 2, new DepositConfirmed(depConfirmed), t1.plusSeconds(1));
			insertEvent(schema, depFailed, 1, new DepositRequested(depFailed, walletId, Money.vnd(250_000), "REF-FAILED-1"), t1.plusSeconds(2));
			insertEvent(schema, depFailed, 2, new DepositFailed(depFailed, "declined"), t1.plusSeconds(3));
			insertEvent(schema, depLong, 1, new DepositRequested(depLong, walletId, Money.vnd(100_000), longRef), t1.plusSeconds(4));

			migrateSchema(schema, "9");

			try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
				try (ResultSet rs = st.executeQuery(
						"SELECT count(*), sum(amount) FROM " + schema + ".deposit_bank_references")) {
					rs.next();
					assertThat(rs.getInt(1)).isEqualTo(3);
					assertThat(rs.getLong(2)).isEqualTo(850_000L);
				}

				try (ResultSet rs = st.executeQuery("""
						SELECT data_type FROM information_schema.columns
						WHERE table_schema = '%s' AND table_name = 'transaction_history' AND column_name = 'bank_reference'
						""".formatted(schema))) {
					rs.next();
					assertThat(rs.getString(1)).isEqualTo("text");
				}

				try (ResultSet rs = st.executeQuery("""
						SELECT data_type FROM information_schema.columns
						WHERE table_schema = '%s' AND table_name = 'idempotency_keys' AND column_name = 'lease_token'
						""".formatted(schema))) {
					rs.next();
					assertThat(rs.getString(1)).isEqualTo("uuid");
				}

				// Case-insensitive unique constraint blocks lowercase duplicate
				assertThatThrownBy(() -> st.executeUpdate("""
						INSERT INTO %s.deposit_bank_references (deposit_id, bank_reference, wallet_id, amount, currency, created_at)
						VALUES ('%s', 'ref-clean-1', '%s', 500000, 'VND', now())
						""".formatted(schema, UUID.randomUUID(), walletId)))
						.hasMessageContaining("uq_deposit_bank_reference_upper");

				// idempotency_keys accepts PENDING and RETRYING statuses
				st.executeUpdate("""
						INSERT INTO %s.idempotency_keys (user_id, idempotency_key, request_hash, status, lease_token, created_at, expires_at)
						VALUES ('%s', 'k-pending', repeat('a', 64), 'PENDING', NULL, now(), now() + interval '1 hour'),
						       ('%s', 'k-retrying', repeat('b', 64), 'RETRYING', '%s', now(), now() + interval '1 hour')
						""".formatted(schema, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
			}
		} finally {
			dropSchema(schema);
		}
	}

	@Test
	void failsFastWithDepositIdsWhenHistoricalEventsContainDuplicateUpperBankReference() throws Exception {
		String schema = "v9_dup_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		createSchema(schema);
		try {
			migrateSchema(schema, "8");

			UUID walletId = UUID.randomUUID();
			UUID dep1 = UUID.randomUUID();
			UUID dep2 = UUID.randomUUID();
			OffsetDateTime t1 = OffsetDateTime.of(2026, 11, 20, 2, 0, 0, 0, ZoneOffset.UTC);

			insertEvent(schema, dep1, 1, new DepositRequested(dep1, walletId, Money.vnd(100_000), "REF-DUP-1"), t1);
			insertEvent(schema, dep2, 1, new DepositRequested(dep2, walletId, Money.vnd(100_000), "ref-dup-1"), t1.plusSeconds(1));

			assertThatThrownBy(() -> migrateSchema(schema, "9"))
					.isInstanceOf(FlywayException.class)
					.hasMessageContaining("REF-DUP-1")
					.hasMessageContaining(dep1.toString())
					.hasMessageContaining(dep2.toString());
		} finally {
			dropSchema(schema);
		}
	}

	private void createSchema(String schema) throws Exception {
		try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
			st.execute("CREATE SCHEMA " + schema);
		}
	}

	private void dropSchema(String schema) throws Exception {
		try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
			st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		}
	}

	private void migrateSchema(String schema, String targetVersion) {
		Flyway.configure()
				.dataSource(dataSource)
				.schemas(schema)
				.defaultSchema(schema)
				.locations("classpath:db/migration")
				.target(targetVersion)
				.load()
				.migrate();
	}

	private void insertEvent(String schema, UUID depositId, long version, DomainEvent event, OffsetDateTime occurredAt)
			throws Exception {
		String sql = "INSERT INTO " + schema + ".events "
				+ "(event_id, aggregate_id, aggregate_type, version, event_type, schema_version, occurred_at, correlation_id, causation_id, payload) "
				+ "VALUES (?, ?, 'Deposit', ?, ?, 1, ?, ?, NULL, ?::jsonb)";
		try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setObject(1, UUID.randomUUID());
			ps.setObject(2, depositId);
			ps.setLong(3, version);
			ps.setString(4, event.eventType());
			ps.setObject(5, occurredAt);
			ps.setObject(6, depositId);
			ps.setString(7, serializer.toJson(event));
			ps.executeUpdate();
		}
	}
}
