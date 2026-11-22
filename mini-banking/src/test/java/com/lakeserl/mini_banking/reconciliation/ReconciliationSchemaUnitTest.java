package com.lakeserl.mini_banking.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class ReconciliationSchemaUnitTest {

	@Test
	void v7MigrationDefinesReconciliationIssuesWithOpenDedupePartialUniqueIndex() throws IOException {
		String sql = readMigration("db/migration/V7__reconciliation_issues.sql");

		assertThat(sql).contains("CREATE TABLE IF NOT EXISTS reconciliation_issues");
		assertThat(sql).contains("'INVARIANT_VIOLATION'");
		assertThat(sql).contains("'CLEARING_NON_ZERO'");
		assertThat(sql).contains("'BALANCE_MISMATCH'");
		assertThat(sql).contains("'BANK_MISMATCH'");
		assertThat(sql).contains("dedupe_key");
		assertThat(sql).contains("status            VARCHAR(20) NOT NULL DEFAULT 'OPEN'");
		assertThat(sql).contains("uq_recon_open_dedupe");
		assertThat(sql).contains("WHERE status = 'OPEN'");
		assertThat(sql).contains("idx_recon_status_detected");
	}

	@Test
	void v8MigrationDefinesPartialIndexesForBankReconciliationAndIdempotencyCleanup() throws IOException {
		String sql = readMigration("db/migration/V8__reconciliation_and_idempotency_indexes.sql");

		assertThat(sql).contains("idx_tx_history_settled_bank");
		assertThat(sql).contains("WHERE tx_type IN ('DEPOSIT', 'WITHDRAWAL') AND status = 'CONFIRMED'");
		assertThat(sql).contains("idx_tx_history_pending_deposit");
		assertThat(sql).contains("WHERE tx_type = 'DEPOSIT' AND status = 'PENDING'");
		assertThat(sql).contains("idx_idempotency_keys_expires_at");
	}

	private static String readMigration(String resourcePath) throws IOException {
		try (InputStream in = ReconciliationSchemaUnitTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
			assertThat(in).as("Migration resource %s must exist", resourcePath).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
