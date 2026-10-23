package com.lakeserl.mini_banking.shared.eventsourcing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.PendingEvent;
import com.lakeserl.mini_banking.wallet.domain.events.WalletOpened;

/** ADR-001: events are never updated or deleted. The database itself enforces it. */
@PostgresDataTest
class EventImmutabilityTest {

	@Autowired
	EventStore store;

	@Autowired
	JdbcTemplate jdbc;

	private UUID storeOneEvent() {
		UUID id = UUID.randomUUID();
		store.append(id, "Wallet", 0,
				List.of(new PendingEvent(UUID.randomUUID(), new WalletOpened(id, UUID.randomUUID(), Currency.VND), id, null)));
		return id;
	}

	@Test
	void updateIsRejected() {
		UUID id = storeOneEvent();
		assertThatThrownBy(() -> jdbc.update("UPDATE events SET payload = '{}'::jsonb WHERE aggregate_id = ?", id))
				.isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
		assertThat(store.load(id, 0).get(0).event()).isInstanceOf(WalletOpened.class);
	}

	@Test
	void deleteIsRejected() {
		UUID id = storeOneEvent();
		assertThatThrownBy(() -> jdbc.update("DELETE FROM events WHERE aggregate_id = ?", id))
				.isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
		assertThat(store.load(id, 0)).hasSize(1);
	}

	@Test
	void truncateIsRejected() {
		UUID id = storeOneEvent();
		assertThatThrownBy(() -> jdbc.execute("TRUNCATE TABLE events"))
				.isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
		assertThat(store.load(id, 0)).hasSize(1);
	}
}
