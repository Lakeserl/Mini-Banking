package com.lakeserl.mini_banking.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.lakeserl.mini_banking.shared.idempotency.IdempotencyService.Decision;

class IdempotencyServiceUnitTest {

	private JdbcTemplate jdbc;
	private TransactionTemplate tx;
	private IdempotencyService service;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		jdbc = mock(JdbcTemplate.class);
		tx = mock(TransactionTemplate.class);
		when(tx.execute(any())).thenAnswer(inv -> {
			TransactionCallback<?> cb = inv.getArgument(0);
			return cb.doInTransaction(null);
		});
		org.mockito.Mockito.doAnswer(inv -> {
			java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action = inv.getArgument(0);
			action.accept(null);
			return null;
		}).when(tx).executeWithoutResult(any());
		service = new IdempotencyService(jdbc, tx, 120L, true);
	}

	@Test
	void rejectsNonPositiveInProgressLeaseSeconds() {
		assertThatThrownBy(() -> new IdempotencyService(jdbc, tx, 0L, true))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new IdempotencyService(jdbc, tx, -5L, true))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void beginReclaimsExpiredOrStaleInProgressRowsAndReturnsFencingToken() {
		UUID userId = UUID.randomUUID();
		String key = "idem-stale";
		String hash = IdempotencyService.sha256("POST", "/api/transfers", "{}".getBytes());
		OffsetDateTime acquiredAt = OffsetDateTime.of(2026, 11, 22, 2, 0, 0, 0, ZoneOffset.UTC);

		when(jdbc.update(anyString(), eq(userId), eq(key), eq(120.0d))).thenReturn(1);
		when(jdbc.queryForList(anyString(), eq(OffsetDateTime.class), eq(userId), eq(key), eq(hash)))
				.thenReturn(List.of(acquiredAt));

		Decision decision = service.begin(userId, key, hash);

		assertThat(decision).isEqualTo(new Decision.Acquired(acquiredAt));
		ArgumentCaptor<String> deleteSql = ArgumentCaptor.forClass(String.class);
		verify(jdbc).update(deleteSql.capture(), eq(userId), eq(key), eq(120.0d));
		assertThat(deleteSql.getValue())
				.contains("expires_at <= now()")
				.contains("status = 'IN_PROGRESS'")
				.contains("make_interval(secs => ?)");

		service.complete(userId, key, acquiredAt, 200, "{\"ok\":true}");
		ArgumentCaptor<String> completeSql = ArgumentCaptor.forClass(String.class);
		verify(jdbc).update(completeSql.capture(), anyString(), eq(userId), eq(key), eq(acquiredAt));
		assertThat(completeSql.getValue()).contains("AND created_at = ?");

		service.release(userId, key, acquiredAt);
		ArgumentCaptor<String> releaseSql = ArgumentCaptor.forClass(String.class);
		verify(jdbc).update(releaseSql.capture(), eq(userId), eq(key), eq(acquiredAt));
		assertThat(releaseSql.getValue()).contains("AND created_at = ?");
	}

	@Test
	void cleanupExpiredDeletesExpiredKeysAndReturnsCount() {
		when(jdbc.update(anyString())).thenReturn(7);

		int deleted = service.cleanupExpired();

		assertThat(deleted).isEqualTo(7);
		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		verify(jdbc).update(sql.capture());
		assertThat(sql.getValue())
				.contains("DELETE FROM idempotency_keys")
				.contains("expires_at <= now()");
	}
}
