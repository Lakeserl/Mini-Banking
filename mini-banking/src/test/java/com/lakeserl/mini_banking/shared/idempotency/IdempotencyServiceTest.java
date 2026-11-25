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
		String hashA = IdempotencyService.sha256("POST", "/api/transfers", "{\"amount\":100}".getBytes());
		String hashB = IdempotencyService.sha256("POST", "/api/transfers", "{\"amount\":999}".getBytes());

		Decision first = service.begin(userId, key, hashA);
		assertThat(first).isInstanceOf(Decision.Acquired.class);
		UUID leaseToken = ((Decision.Acquired) first).leaseToken();

		assertThat(service.begin(userId, key, hashA)).isInstanceOf(Decision.InProgress.class);

		service.complete(userId, key, leaseToken, 200, "{\"status\":\"COMPLETED\"}");

		Decision replay = service.begin(userId, key, hashA);
		assertThat(replay).isEqualTo(new Decision.Completed(200, "{\"status\":\"COMPLETED\"}"));

		assertThat(service.begin(userId, key, hashB)).isInstanceOf(Decision.HashMismatch.class);
	}

	@Test
	void keysAreScopedPerUserAndReleaseAllowsRetry() {
		UUID userA = UUID.randomUUID();
		UUID userB = UUID.randomUUID();
		String key = "shared-key-" + UUID.randomUUID();
		String hash = IdempotencyService.sha256("POST", "/api/wallets/1/withdraw", "{}".getBytes());

		Decision acqA = service.begin(userA, key, hash);
		assertThat(acqA).isInstanceOf(Decision.Acquired.class);
		assertThat(service.begin(userB, key, hash)).isInstanceOf(Decision.Acquired.class);

		service.release(userA, key, ((Decision.Acquired) acqA).leaseToken());
		assertThat(service.begin(userA, key, hash)).isInstanceOf(Decision.Acquired.class);
	}

	@Test
	void expiredKeyCanBeReacquired() {
		UUID userId = UUID.randomUUID();
		String key = "expired-" + UUID.randomUUID();
		String hash = IdempotencyService.sha256("POST", "/api/wallets/1/deposit", "{}".getBytes());

		UUID leaseToken = ((Decision.Acquired) service.begin(userId, key, hash)).leaseToken();
		service.complete(userId, key, leaseToken, 200, "{\"ok\":true}");
		jdbc.update("UPDATE idempotency_keys SET expires_at = now() - interval '1 minute' WHERE user_id = ? AND idempotency_key = ?",
				userId, key);

		assertThat(service.begin(userId, key, hash)).isInstanceOf(Decision.Acquired.class);
	}

	@Test
	void pendingLifecycleSupportsRetryRevertOnReleaseStaleRetryingReclaimAndCrossEndpoint422() {
		UUID userId = UUID.randomUUID();
		String key = "dep-pending-" + UUID.randomUUID();
		String depositHash = IdempotencyService.sha256("POST", "/api/wallets/1/deposit", "{\"amount\":500000}".getBytes());
		String transferHash = IdempotencyService.sha256("POST", "/api/transfers", "{\"amount\":500000}".getBytes());

		UUID token1 = ((Decision.Acquired) service.begin(userId, key, depositHash)).leaseToken();
		service.markPending(userId, key, token1);

		// Different endpoint/payload while PENDING -> 422 HashMismatch
		assertThat(service.begin(userId, key, transferHash)).isInstanceOf(Decision.HashMismatch.class);

		// Same payload transitions PENDING -> RETRYING with new lease token; concurrent caller gets InProgress
		UUID token2 = ((Decision.Acquired) service.begin(userId, key, depositHash)).leaseToken();
		assertThat(token2).isNotEqualTo(token1);
		assertThat(service.begin(userId, key, depositHash)).isInstanceOf(Decision.InProgress.class);

		// Stale token1 cannot complete or release token2's active lease
		service.complete(userId, key, token1, 200, "{\"stale\":true}");
		assertThat(service.begin(userId, key, depositHash)).isInstanceOf(Decision.InProgress.class);

		// Releasing RETRYING reverts back to PENDING preserving request_hash (422 still enforced!)
		service.release(userId, key, token2);
		assertThat(service.begin(userId, key, transferHash)).isInstanceOf(Decision.HashMismatch.class);

		// Re-acquire into RETRYING, simulate crash > 120s -> stale RETRYING reverts to PENDING, NOT deleted!
		UUID token3 = ((Decision.Acquired) service.begin(userId, key, depositHash)).leaseToken();
		jdbc.update("UPDATE idempotency_keys SET created_at = now() - interval '5 minutes' WHERE user_id = ? AND idempotency_key = ?",
				userId, key);
		assertThat(service.begin(userId, key, transferHash)).isInstanceOf(Decision.HashMismatch.class);

		// Same payload now re-acquires and completes cleanly
		UUID token4 = ((Decision.Acquired) service.begin(userId, key, depositHash)).leaseToken();
		assertThat(token4).isNotEqualTo(token3);
		service.complete(userId, key, token4, 200, "{\"status\":\"CONFIRMED\"}");

		assertThat(service.begin(userId, key, depositHash))
				.isEqualTo(new Decision.Completed(200, "{\"status\":\"CONFIRMED\"}"));
	}

	@Test
	void staleInProgressKeyIsReclaimedAfterLeaseExpiryAndCleanupRemovesExpiredRows() {
		UUID userId = UUID.randomUUID();
		String staleKey = "stale-in-progress-" + UUID.randomUUID();
		String expiredKey = "expired-cleanup-" + UUID.randomUUID();
		String hash = IdempotencyService.sha256("POST", "/api/transfers", "{}".getBytes());

		assertThat(service.begin(userId, staleKey, hash)).isInstanceOf(Decision.Acquired.class);
		assertThat(service.begin(userId, staleKey, hash)).isInstanceOf(Decision.InProgress.class);
		jdbc.update("UPDATE idempotency_keys SET created_at = now() - interval '5 minutes' WHERE user_id = ? AND idempotency_key = ?",
				userId, staleKey);

		assertThat(service.begin(userId, staleKey, hash)).isInstanceOf(Decision.Acquired.class);

		UUID expToken = ((Decision.Acquired) service.begin(userId, expiredKey, hash)).leaseToken();
		service.complete(userId, expiredKey, expToken, 200, "{\"ok\":true}");
		jdbc.update("UPDATE idempotency_keys SET expires_at = now() - interval '1 minute' WHERE user_id = ? AND idempotency_key = ?",
				userId, expiredKey);

		assertThat(service.cleanupExpired()).isGreaterThanOrEqualTo(1);
	}
}
