package com.lakeserl.mini_banking.ledger.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class LedgerSchemaUnitTest {

	private static final String MIGRATION_PATH = "db/migration/V5__ledger_projection.sql";

	private String loadNormalizedSql() throws Exception {
		ClassPathResource resource = new ClassPathResource(MIGRATION_PATH);
		assertThat(resource.exists())
				.as("Expected Flyway migration %s to exist on classpath", MIGRATION_PATH)
				.isTrue();
		return resource.getContentAsString(StandardCharsets.UTF_8)
				.replaceAll("\\s+", " ")
				.trim();
	}

	@Test
	void definesProcessedEventsTableWithCompositePrimaryKey() throws Exception {
		String sql = loadNormalizedSql();
		assertThat(sql).contains("CREATE TABLE IF NOT EXISTS processed_events");
		assertThat(sql).contains("consumer_group VARCHAR(50) NOT NULL");
		assertThat(sql).contains("event_id UUID NOT NULL");
		assertThat(sql).contains("processed_at TIMESTAMPTZ NOT NULL");
		assertThat(sql).contains("PRIMARY KEY (consumer_group, event_id)");
	}

	@Test
	void definesLedgerEntriesTableWithColumnsChecksAndCompositeUniqueConstraint() throws Exception {
		String sql = loadNormalizedSql();
		assertThat(sql).contains("CREATE TABLE IF NOT EXISTS ledger_entries");
		assertThat(sql).contains("id BIGSERIAL PRIMARY KEY");
		assertThat(sql).contains("transaction_id UUID NOT NULL");
		assertThat(sql).contains("account_id VARCHAR(50) NOT NULL");
		assertThat(sql).contains("account_type VARCHAR(30) NOT NULL CHECK (account_type IN ('USER_WALLET', 'SETTLEMENT', 'CLEARING'))");
		assertThat(sql).contains("entry_type VARCHAR(10) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT'))");
		assertThat(sql).contains("amount BIGINT NOT NULL CHECK (amount > 0)");
		assertThat(sql).contains("currency VARCHAR(3) NOT NULL DEFAULT 'VND'");
		assertThat(sql).contains("occurred_at TIMESTAMPTZ NOT NULL");
		assertThat(sql).contains("CONSTRAINT uq_ledger_event_entry_type UNIQUE (event_id, entry_type)");
	}

	@Test
	void doesNotHaveSingleColumnEventIdUniqueBug() throws Exception {
		String lowerSql = loadNormalizedSql().toLowerCase(Locale.ROOT);
		assertThat(lowerSql).doesNotContainPattern("event_id\\s+uuid\\s+not\\s+null\\s+unique\\b");
		assertThat(lowerSql).doesNotContainPattern("unique\\s*\\(\\s*event_id\\s*\\)");
	}

	@Test
	void definesTransactionAndAccountIndexes() throws Exception {
		String sql = loadNormalizedSql();
		assertThat(sql).contains("CREATE INDEX IF NOT EXISTS idx_ledger_tx ON ledger_entries (transaction_id)");
		assertThat(sql).contains("CREATE INDEX IF NOT EXISTS idx_ledger_account ON ledger_entries (account_id, occurred_at)");
	}
}
