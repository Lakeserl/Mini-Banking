package com.lakeserl.mini_banking.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.shared.idempotency.IdempotencyService.Decision;

@PostgresDataTest
@Import(IdempotencyService.class)
class IdempotencyServiceTest {

	@Autowired
	IdempotencyService service;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void beginCompleteReplayAndRejectMismatchedPayload() {
		UUID userId = UUID.randomUUID();
		String key = "idem-" + UUID.randomUUID();
		String hashA = IdempotencyService.sha256("POST", "/api/wallets/1/deposit", "{\"amount\":100}".getBytes());
		String hashB = IdempotencyService.sha256("POST", "/api/wallets/1/deposit", "{\"amount\":999}".getBytes());

		assertThat(service.begin(userId, key, hashA)).isInstanceOf(Decision.Acquired.class);
		assertThat(service.begin(userId, key, hashA)).isInstanceOf(Decision.InProgress.class);

		service.complete(userId, key, 200, "{\"status\":\"CONFIRMED\"}");

		Decision replay = service.begin(userId, key, hashA);
		assertThat(replay).isEqualTo(new Decision.Completed(200, "{\"status\":\"CONFIRMED\"}"));

		assertThat(service.begin(userId, key, hashB)).isInstanceOf(Decision.HashMismatch.class);
	}

	@Test
	void keysAreScopedPerUserAndReleaseAllowsRetry() {
		UUID userA = UUID.randomUUID();
		UUID userB = UUID.randomUUID();
		String key = "shared-key-" + UUID.randomUUID();
		String hash = IdempotencyService.sha256("POST", "/api/wallets/1/withdraw", "{}".getBytes());

		assertThat(service.begin(userA, key, hash)).isInstanceOf(Decision.Acquired.class);
		assertThat(service.begin(userB, key, hash)).isInstanceOf(Decision.Acquired.class);

		service.release(userA, key);
		assertThat(service.begin(userA, key, hash)).isInstanceOf(Decision.Acquired.class);
	}

	@Test
	void expiredKeyCanBeReacquired() {
		UUID userId = UUID.randomUUID();
		String key = "expired-" + UUID.randomUUID();
		String hash = IdempotencyService.sha256("POST", "/api/wallets/1/deposit", "{}".getBytes());

		assertThat(service.begin(userId, key, hash)).isInstanceOf(Decision.Acquired.class);
		service.complete(userId, key, 200, "{\"ok\":true}");
		jdbc.update("UPDATE idempotency_keys SET expires_at = now() - interval '1 minute' WHERE user_id = ? AND idempotency_key = ?",
				userId, key);

		assertThat(service.begin(userId, key, hash)).isInstanceOf(Decision.Acquired.class);
	}
}
