package com.lakeserl.mini_banking.ledger.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lakeserl.mini_banking.PostgresDataTest;

@PostgresDataTest
class LedgerSchemaTest {

	@Autowired
	JdbcTemplate jdbc;

	private List<String> columns(String table) {
		return jdbc.queryForList(
				"SELECT column_name FROM information_schema.columns WHERE table_name = ? ORDER BY column_name",
				String.class, table);
	}

	@Test
	void processedEventsHasExpectedColumns() {
		assertThat(columns("processed_events")).containsExactlyInAnyOrder(
				"consumer_group", "event_id", "processed_at");
	}

	@Test
	void ledgerEntriesHasExpectedColumns() {
		assertThat(columns("ledger_entries")).containsExactlyInAnyOrder(
				"id", "transaction_id", "account_id", "account_type", "entry_type",
				"amount", "currency", "occurred_at", "event_id");
	}

	@Test
	void doubleEntryPairWithSameEventIdSucceeds() {
		UUID txId = UUID.randomUUID();
		UUID eventId = UUID.randomUUID();
		String insert = """
				INSERT INTO ledger_entries
				    (transaction_id, account_id, account_type, entry_type, amount, occurred_at, event_id)
				VALUES (?, ?, ?, ?, ?, now(), ?)
				""";

		jdbc.update(insert, txId, UUID.randomUUID().toString(), "USER_WALLET", "DEBIT", 50_000L, eventId);
		jdbc.update(insert, txId, "TRANSFER_CLEARING", "CLEARING", "CREDIT", 50_000L, eventId);

		Integer count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM ledger_entries WHERE event_id = ?", Integer.class, eventId);
		assertThat(count).isEqualTo(2);

		List<String> currencies = jdbc.queryForList(
				"SELECT currency FROM ledger_entries WHERE event_id = ?", String.class, eventId);
		assertThat(currencies).containsOnly("VND");
	}

	@Test
	void duplicateEntryTypeForSameEventIdIsRejected() {
		UUID txId = UUID.randomUUID();
		UUID eventId = UUID.randomUUID();
		String insert = """
				INSERT INTO ledger_entries
				    (transaction_id, account_id, account_type, entry_type, amount, currency, occurred_at, event_id)
				VALUES (?, ?, 'USER_WALLET', 'DEBIT', 10000, 'VND', now(), ?)
				""";

		jdbc.update(insert, txId, UUID.randomUUID().toString(), eventId);
		assertThatThrownBy(() -> jdbc.update(insert, txId, UUID.randomUUID().toString(), eventId))
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void nonPositiveAmountIsRejected() {
		String insert = """
				INSERT INTO ledger_entries
				    (transaction_id, account_id, account_type, entry_type, amount, currency, occurred_at, event_id)
				VALUES (?, 'BANK_SETTLEMENT', 'SETTLEMENT', 'DEBIT', ?, 'VND', now(), ?)
				""";

		assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), 0L, UUID.randomUUID()))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), -100L, UUID.randomUUID()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void invalidEntryTypeIsRejected() {
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO ledger_entries
				    (transaction_id, account_id, account_type, entry_type, amount, currency, occurred_at, event_id)
				VALUES (?, 'BANK_SETTLEMENT', 'SETTLEMENT', 'INVALID', 10000, 'VND', now(), ?)
				""", UUID.randomUUID(), UUID.randomUUID()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void invalidAccountTypeIsRejected() {
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO ledger_entries
				    (transaction_id, account_id, account_type, entry_type, amount, currency, occurred_at, event_id)
				VALUES (?, 'EXTERNAL_BANK', 'EXTERNAL', 'DEBIT', 10000, 'VND', now(), ?)
				""", UUID.randomUUID(), UUID.randomUUID()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void processedEventsRejectsDuplicateConsumerGroupAndEventId() {
		UUID eventId = UUID.randomUUID();
		String insert = """
				INSERT INTO processed_events (consumer_group, event_id, processed_at)
				VALUES (?, ?, now())
				""";

		jdbc.update(insert, "ledger-projection", eventId);
		assertThatThrownBy(() -> jdbc.update(insert, "ledger-projection", eventId))
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void processedEventsAllowsSameEventIdForDifferentConsumerGroups() {
		UUID eventId = UUID.randomUUID();
		String insert = """
				INSERT INTO processed_events (consumer_group, event_id, processed_at)
				VALUES (?, ?, now())
				""";

		jdbc.update(insert, "ledger-projection", eventId);
		jdbc.update(insert, "balance-projection", eventId);

		Integer count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId);
		assertThat(count).isEqualTo(2);
	}
}
