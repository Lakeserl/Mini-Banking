package com.lakeserl.mini_banking.query.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class QuerySchemaUnitTest {

	@Test
	void v6MigrationDefinesEventSeqOutboxVersionAndQueryProjectionTables() throws IOException {
		String sql = readMigration("db/migration/V6__query_projections_and_event_seq.sql");

		assertThat(sql).contains("ALTER TABLE events ADD COLUMN IF NOT EXISTS global_seq BIGSERIAL NOT NULL");
		assertThat(sql).contains("uq_events_global_seq");
		assertThat(sql).contains("idx_events_occurred_at");
		assertThat(sql).contains("ALTER TABLE outbox ADD COLUMN IF NOT EXISTS aggregate_version BIGINT");

		assertThat(sql).contains("CREATE TABLE IF NOT EXISTS wallet_balances");
		assertThat(sql).contains("balance            BIGINT NOT NULL CHECK (balance >= 0)");
		assertThat(sql).contains("aggregate_version  BIGINT NOT NULL CHECK (aggregate_version >= 1)");
		assertThat(sql).contains("idx_wallet_balances_owner");

		assertThat(sql).contains("CREATE TABLE IF NOT EXISTS transaction_history");
		assertThat(sql).contains("uq_tx_history_tx_wallet UNIQUE (transaction_id, wallet_id)");
		assertThat(sql).contains("idx_tx_history_wallet_cursor");
		assertThat(sql).contains("idx_tx_history_tx_id");

		assertThat(sql).contains("CREATE TABLE IF NOT EXISTS daily_reports");
		assertThat(sql).contains("PRIMARY KEY (report_date, currency)");
	}

	private static String readMigration(String resourcePath) throws IOException {
		try (InputStream in = QuerySchemaUnitTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
			assertThat(in).as("Migration resource %s must exist", resourcePath).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
