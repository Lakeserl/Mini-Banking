package com.lakeserl.mini_banking.deposit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class DepositGuardSchemaUnitTest {

	@Test
	void v9MigrationDefinesImmutableBankReferenceGuardFailFastBackfillTextColumnAndIdempotencyLeaseToken()
			throws IOException {
		String sql = readMigration("db/migration/V9__deposit_bank_reference_guard.sql");

		assertThat(sql).contains("CREATE TABLE IF NOT EXISTS deposit_bank_references");
		assertThat(sql).contains("deposit_id     UUID PRIMARY KEY");
		assertThat(sql).contains("bank_reference TEXT NOT NULL");
		assertThat(sql).contains("RAISE EXCEPTION");
		assertThat(sql).contains("payload->'amount'->>'amount'");
		assertThat(sql).contains("uq_deposit_bank_reference_upper");
		assertThat(sql).contains("UPPER(bank_reference)");
		assertThat(sql).contains("ALTER TABLE transaction_history");
		assertThat(sql).contains("ALTER COLUMN bank_reference TYPE TEXT");
		assertThat(sql).contains("ADD COLUMN IF NOT EXISTS lease_token UUID");
		assertThat(sql).contains("chk_idempotency_keys_status");
		assertThat(sql).contains("'IN_PROGRESS', 'PENDING', 'RETRYING', 'COMPLETED'");
	}

	private static String readMigration(String resourcePath) throws IOException {
		try (InputStream in = DepositGuardSchemaUnitTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
			assertThat(in).as("Migration resource %s must exist", resourcePath).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
