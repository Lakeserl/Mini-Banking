package com.lakeserl.mini_banking.shared.eventsourcing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.domain.PendingEvent;
import com.lakeserl.mini_banking.wallet.domain.events.WalletCredited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletOpened;

@PostgresDataTest
class PostgresEventStoreOutboxTest {

	@Autowired
	EventStore store;

	@Autowired
	JdbcTemplate jdbc;

	private static PendingEvent opened(UUID walletId) {
		return new PendingEvent(UUID.randomUUID(), new WalletOpened(walletId, UUID.randomUUID(), Currency.VND),
				walletId, null);
	}

	private static PendingEvent credited(UUID walletId, long amount) {
		UUID tx = UUID.randomUUID();
		return new PendingEvent(UUID.randomUUID(),
				new WalletCredited(walletId, Money.vnd(amount), tx, "BANK_SETTLEMENT", AccountType.SETTLEMENT), tx,
				null);
	}

	@Test
	void appendWritesOneOutboxRowPerEvent() {
		UUID id = UUID.randomUUID();
		List<PendingEvent> events = List.of(opened(id), credited(id, 1_000), credited(id, 2_000));
		store.append(id, "Wallet", 0, events);

		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT o.id, o.event_id, o.aggregate_type, o.event_type, o.published_at,
				       o.payload = e.payload AS same_payload
				FROM outbox o JOIN events e ON e.event_id = o.event_id
				WHERE o.aggregate_id = ? ORDER BY o.id
				""", id);

		assertThat(rows).hasSize(3);
		for (int i = 0; i < 3; i++) {
			assertThat(rows.get(i).get("event_id")).isEqualTo(events.get(i).eventId());
			assertThat(rows.get(i).get("aggregate_type")).isEqualTo("Wallet");
			assertThat(rows.get(i).get("event_type")).isEqualTo(events.get(i).event().eventType());
			assertThat(rows.get(i).get("published_at")).isNull();
			assertThat(rows.get(i).get("same_payload")).isEqualTo(true);
		}
	}

	@Test
	void appendRollsBackOutboxWhenBatchFails() {
		UUID id = UUID.randomUUID();
		PendingEvent first = opened(id);
		PendingEvent duplicateOfFirst = new PendingEvent(first.eventId(), credited(id, 1).event(), id, null);

		assertThatThrownBy(() -> store.append(id, "Wallet", 0, List.of(first, duplicateOfFirst)))
				.isInstanceOf(DuplicateEventException.class);

		Integer count = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?", Integer.class, id);
		assertThat(count).isZero();
	}
}
