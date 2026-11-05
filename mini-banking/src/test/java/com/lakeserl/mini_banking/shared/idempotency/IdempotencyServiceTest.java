package com.lakeserl.mini_banking.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lakeserl.mini_banking.PostgresDataTest;

@PostgresDataTest
class IdempotencyServiceTest {

	@Autowired
	JdbcTemplate jdbc;

	private AtomicReference<Instant> currentInstant;
	private IdempotencyService service;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM idempotency_keys");
		currentInstant = new AtomicReference<>(Instant.parse("2026-11-05T10:00:00Z"));
		Clock mutableClock = new Clock() {
			@Override
			public ZoneOffset getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				return currentInstant.get();
			}
		};
		service = new IdempotencyService(jdbc, mutableClock);
	}

	@Test
	void begin_newKeyReturnsNew() {
		UUID userId = UUID.randomUUID();
		String hash = IdempotencyService.hash("{\"amount\":1000}");

		IdempotencyResult result = service.begin(userId, "key-1", hash);

		assertThat(result).isInstanceOf(IdempotencyResult.New.class);
	}

	@Test
	void begin_inProgressReturnsInProgress() {
		UUID userId = UUID.randomUUID();
		String hash = IdempotencyService.hash("{\"amount\":1000}");

		assertThat(service.begin(userId, "key-1", hash)).isInstanceOf(IdempotencyResult.New.class);
		assertThat(service.begin(userId, "key-1", hash)).isInstanceOf(IdempotencyResult.InProgress.class);
	}

	@Test
	void complete_thenSameHashReplaysPayload() {
		UUID userId = UUID.randomUUID();
		String hash = IdempotencyService.hash("{\"amount\":1000}");

		service.begin(userId, "key-1", hash);
		service.complete(userId, "key-1", "{\"status\":\"OK\",\"balance\":1000}");

		IdempotencyResult replay = service.begin(userId, "key-1", hash);
		assertThat(replay).isEqualTo(new IdempotencyResult.Replay("{\"status\": \"OK\", \"balance\": 1000}"));
	}

	@Test
	void complete_thenDifferentHashReturnsHashMismatch() {
		UUID userId = UUID.randomUUID();
		String hash1 = IdempotencyService.hash("{\"amount\":1000}");
		String hash2 = IdempotencyService.hash("{\"amount\":2000}");

		service.begin(userId, "key-1", hash1);
		service.complete(userId, "key-1", "{\"status\":\"OK\"}");

		assertThat(service.begin(userId, "key-1", hash2)).isInstanceOf(IdempotencyResult.HashMismatch.class);
	}

	@Test
	void begin_expiredRecordIsTreatedAsNew() {
		UUID userId = UUID.randomUUID();
		String hash1 = IdempotencyService.hash("{\"amount\":1000}");
		String hash2 = IdempotencyService.hash("{\"amount\":9999}");

		service.begin(userId, "key-1", hash1);
		service.complete(userId, "key-1", "{\"status\":\"OLD\"}");

		currentInstant.updateAndGet(t -> t.plus(Duration.ofHours(24).plusSeconds(1)));

		assertThat(service.begin(userId, "key-1", hash2)).isInstanceOf(IdempotencyResult.New.class);
	}

	@Test
	void begin_sameKeyDifferentUserIsIndependent() {
		UUID userA = UUID.randomUUID();
		UUID userB = UUID.randomUUID();
		String hashA = IdempotencyService.hash("{\"amount\":100}");
		String hashB = IdempotencyService.hash("{\"amount\":200}");

		assertThat(service.begin(userA, "shared-key", hashA)).isInstanceOf(IdempotencyResult.New.class);
		assertThat(service.begin(userB, "shared-key", hashB)).isInstanceOf(IdempotencyResult.New.class);
	}

	@Test
	void begin_concurrentCallsYieldExactlyOneNew() throws Exception {
		UUID userId = UUID.randomUUID();
		String hash = IdempotencyService.hash("{\"amount\":5000}");
		int threads = 16;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<IdempotencyResult>> futures = new ArrayList<>();

		for (int i = 0; i < threads; i++) {
			futures.add(pool.submit(() -> {
				ready.countDown();
				start.await(5, TimeUnit.SECONDS);
				return service.begin(userId, "concurrent-key", hash);
			}));
		}
		ready.await(5, TimeUnit.SECONDS);
		start.countDown();

		int newCount = 0;
		int inProgressCount = 0;
		for (Future<IdempotencyResult> f : futures) {
			IdempotencyResult r = f.get(5, TimeUnit.SECONDS);
			if (r instanceof IdempotencyResult.New) {
				newCount++;
			} else if (r instanceof IdempotencyResult.InProgress) {
				inProgressCount++;
			}
		}
		pool.shutdownNow();

		assertThat(newCount).isEqualTo(1);
		assertThat(inProgressCount).isEqualTo(threads - 1);
	}

	@Test
	void hash_matchesKnownSha256Vector() {
		assertThat(IdempotencyService.hash("abc"))
				.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}

	@Test
	void begin_rejectsNullOrBlankKey() {
		UUID userId = UUID.randomUUID();
		String hash = IdempotencyService.hash("abc");

		assertThatThrownBy(() -> service.begin(userId, null, hash)).isInstanceOf( NullPointerException.class);
		assertThatThrownBy(() -> service.begin(userId, "   ", hash)).isInstanceOf(IllegalArgumentException.class);
	}
}
