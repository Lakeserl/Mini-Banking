package com.lakeserl.mini_banking.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
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
	void rejectsNonPositiveInProgressLeaseSecondsAndNullLeaseToken() {
		assertThatThrownBy(() -> new IdempotencyService(jdbc, tx, 0L, true))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new IdempotencyService(jdbc, tx, -5L, true))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Decision.Acquired(null))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> service.markPending(UUID.randomUUID(), "k", null))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> service.complete(UUID.randomUUID(), "k", null, 200, "{}"))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> service.release(UUID.randomUUID(), "k", null))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void beginReclaimsStaleLeasesInsertsWithUuidLeaseTokenAndFencesCompleteMarkPendingAndRelease() {
		UUID userId = UUID.randomUUID();
		String key = "idem-stale";
		String hash = IdempotencyService.sha256("POST", "/api/transfers", "{}".getBytes());

		when(jdbc.update(anyString(), eq(userId), eq(key), eq(120.0d))).thenReturn(1);
		when(jdbc.update(anyString(), eq(userId), eq(key), eq(hash), any(UUID.class))).thenReturn(1);

		Decision decision = service.begin(userId, key, hash);

		assertThat(decision).isInstanceOf(Decision.Acquired.class);
		UUID leaseToken = ((Decision.Acquired) decision).leaseToken();
		assertThat(leaseToken).isNotNull();

		ArgumentCaptor<String> cleanupSqls = ArgumentCaptor.forClass(String.class);
		verify(jdbc, times(2)).update(cleanupSqls.capture(), eq(userId), eq(key), eq(120.0d));
		assertThat(cleanupSqls.getAllValues().get(0))
				.contains("DELETE FROM idempotency_keys")
				.contains("status = 'IN_PROGRESS'");
		assertThat(cleanupSqls.getAllValues().get(1))
				.contains("SET status = 'PENDING', lease_token = NULL")
				.contains("status = 'RETRYING'");

		service.markPending(userId, key, leaseToken);
		ArgumentCaptor<String> pendingSql = ArgumentCaptor.forClass(String.class);
		verify(jdbc).update(pendingSql.capture(), eq(userId), eq(key), eq(leaseToken));
		assertThat(pendingSql.getValue())
				.contains("SET status = 'PENDING', lease_token = NULL")
				.contains("AND lease_token = ?");

		service.complete(userId, key, leaseToken, 200, "{\"ok\":true}");
		ArgumentCaptor<String> completeSql = ArgumentCaptor.forClass(String.class);
		verify(jdbc).update(completeSql.capture(), anyString(), eq(userId), eq(key), eq(leaseToken));
		assertThat(completeSql.getValue())
				.contains("SET status = 'COMPLETED'")
				.contains("AND lease_token = ?");

		service.release(userId, key, leaseToken);
		ArgumentCaptor<String> releaseSqls = ArgumentCaptor.forClass(String.class);
		verify(jdbc, times(3)).update(releaseSqls.capture(), eq(userId), eq(key), eq(leaseToken));
		assertThat(releaseSqls.getAllValues().get(1))
				.contains("DELETE FROM idempotency_keys")
				.contains("status = 'IN_PROGRESS'")
				.contains("AND lease_token = ?");
		assertThat(releaseSqls.getAllValues().get(2))
				.contains("SET status = 'PENDING', lease_token = NULL")
				.contains("status = 'RETRYING'")
				.contains("AND lease_token = ?");
	}

	@Test
	@SuppressWarnings("unchecked")
	void beginReturnsInProgressInsteadOfThrowingWhenFallbackQueryFindsEmptyListDueToConcurrentDelete() {
		UUID userId = UUID.randomUUID();
		String key = "idem-race-delete";
		String hash = IdempotencyService.sha256("POST", "/api/wallets/1/deposit", "{}".getBytes());

		when(jdbc.update(anyString(), any(), any(), any())).thenReturn(0);
		when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(0);
		when(jdbc.query(anyString(), any(RowMapper.class), eq(userId), eq(key))).thenReturn(List.of());

		assertThat(service.begin(userId, key, hash)).isEqualTo(new Decision.InProgress());
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
