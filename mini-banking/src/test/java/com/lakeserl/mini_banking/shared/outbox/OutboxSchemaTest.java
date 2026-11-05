package com.lakeserl.mini_banking.shared.outbox;

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
class OutboxSchemaTest {

	@Autowired
	JdbcTemplate jdbc;

	private List<String> columns(String table) {
		return jdbc.queryForList(
				"SELECT column_name FROM information_schema.columns WHERE table_name = ? ORDER BY column_name",
				String.class, table);
	}

	@Test
	void outboxHasExpectedColumns() {
		assertThat(columns("outbox")).containsExactlyInAnyOrder("id", "event_id", "aggregate_id", "aggregate_type",
				"event_type", "payload", "created_at", "published_at");
	}

	@Test
	void idempotencyKeysHasExpectedColumns() {
		assertThat(columns("idempotency_keys")).containsExactlyInAnyOrder("user_id", "idempotency_key", "request_hash",
				"status", "response_payload", "created_at", "expires_at");
	}

	@Test
	void duplicateOutboxEventIdIsRejected() {
		UUID eventId = UUID.randomUUID();
		String sql = """
				INSERT INTO outbox (event_id, aggregate_id, aggregate_type, event_type, payload, created_at)
				VALUES (?, ?, 'Wallet', 'WalletOpened', '{}'::jsonb, now())
				""";
		jdbc.update(sql, eventId, UUID.randomUUID());
		assertThatThrownBy(() -> jdbc.update(sql, eventId, UUID.randomUUID()))
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void idempotencyStatusOutsideAllowedValuesIsRejected() {
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash, status, created_at, expires_at)
				VALUES (?, 'k', repeat('a', 64), 'BOGUS', now(), now() + interval '24 hours')
				""", UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
	}
}
