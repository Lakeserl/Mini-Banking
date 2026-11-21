package com.lakeserl.mini_banking.query.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.lakeserl.mini_banking.deposit.domain.events.DepositConfirmed;
import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.wallet.domain.events.WalletCredited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletDebited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletLocked;
import com.lakeserl.mini_banking.wallet.domain.events.WalletOpened;

class BalanceProjectorUnitTest {

	record BalanceRow(UUID walletId, UUID ownerId, String currency, long balance, String status, long version,
			OffsetDateTime updatedAt) {
	}

	private JdbcTemplate jdbc;
	private TransactionTemplate tx;
	private BalanceProjector projector;
	private final Set<String> processed = new HashSet<>();
	private final Map<UUID, BalanceRow> balances = new HashMap<>();

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		jdbc = mock(JdbcTemplate.class);
		tx = mock(TransactionTemplate.class);
		when(tx.execute(any(TransactionCallback.class))).thenAnswer(inv -> {
			TransactionCallback<Object> cb = inv.getArgument(0);
			Set<String> processedSnapshot = new HashSet<>(processed);
			Map<UUID, BalanceRow> balancesSnapshot = new HashMap<>(balances);
			try {
				return cb.doInTransaction(mock(TransactionStatus.class));
			} catch (RuntimeException ex) {
				processed.clear();
				processed.addAll(processedSnapshot);
				balances.clear();
				balances.putAll(balancesSnapshot);
				throw ex;
			}
		});

		when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
			String sql = inv.getArgument(0);
			Object[] args = inv.getArguments();
			if (sql.contains("INSERT INTO processed_events")) {
				String key = args[1] + ":" + args[2];
				return processed.add(key) ? 1 : 0;
			}
			if (sql.contains("INSERT INTO wallet_balances")) {
				UUID walletId = (UUID) args[1];
				UUID ownerId = (UUID) args[2];
				String currency = (String) args[3];
				long version = (Long) args[4];
				OffsetDateTime updatedAt = (OffsetDateTime) args[5];
				balances.put(walletId, new BalanceRow(walletId, ownerId, currency, 0L, "ACTIVE", version, updatedAt));
				return 1;
			}
			if (sql.contains("SET balance = balance + ?")) {
				long delta = (Long) args[1];
				long version = (Long) args[2];
				OffsetDateTime updatedAt = (OffsetDateTime) args[3];
				UUID walletId = (UUID) args[4];
				BalanceRow row = balances.get(walletId);
				if (row == null) {
					return 0;
				}
				long next = Math.addExact(row.balance(), delta);
				balances.put(walletId, new BalanceRow(row.walletId(), row.ownerId(), row.currency(), next, row.status(),
						Math.max(row.version(), version), updatedAt));
				return 1;
			}
			if (sql.contains("SET balance = balance - ?")) {
				long delta = (Long) args[1];
				long version = (Long) args[2];
				OffsetDateTime updatedAt = (OffsetDateTime) args[3];
				UUID walletId = (UUID) args[4];
				BalanceRow row = balances.get(walletId);
				if (row == null || row.balance() < delta) {
					return 0;
				}
				long next = Math.subtractExact(row.balance(), delta);
				balances.put(walletId, new BalanceRow(row.walletId(), row.ownerId(), row.currency(), next, row.status(),
						Math.max(row.version(), version), updatedAt));
				return 1;
			}
			if (sql.contains("SET status = 'LOCKED'")) {
				long version = (Long) args[1];
				OffsetDateTime updatedAt = (OffsetDateTime) args[2];
				UUID walletId = (UUID) args[3];
				BalanceRow row = balances.get(walletId);
				if (row == null) {
					return 0;
				}
				balances.put(walletId, new BalanceRow(row.walletId(), row.ownerId(), row.currency(), row.balance(),
						"LOCKED", Math.max(row.version(), version), updatedAt));
				return 1;
			}
			throw new IllegalStateException("Unexpected SQL: " + sql);
		});

		projector = new BalanceProjector(jdbc, tx);
	}

	@Test
	void walletLifecycleUpdatesBalanceStatusAndAggregateVersion() {
		UUID walletId = UUID.randomUUID();
		UUID ownerId = UUID.randomUUID();
		Instant t1 = Instant.parse("2026-11-21T01:00:00Z");
		Instant t2 = Instant.parse("2026-11-21T01:05:00Z");
		Instant t3 = Instant.parse("2026-11-21T01:10:00Z");
		Instant t4 = Instant.parse("2026-11-21T01:15:00Z");

		assertThat(projector.project(UUID.randomUUID(), new WalletOpened(walletId, ownerId, Currency.VND), 1L, t1))
				.isTrue();
		assertThat(balances.get(walletId).balance()).isZero();
		assertThat(balances.get(walletId).status()).isEqualTo("ACTIVE");
		assertThat(balances.get(walletId).version()).isEqualTo(1L);

		assertThat(projector.project(UUID.randomUUID(),
				new WalletCredited(walletId, Money.vnd(500_000), UUID.randomUUID(), "BANK_SETTLEMENT",
						AccountType.SETTLEMENT),
				2L, t2)).isTrue();
		assertThat(balances.get(walletId).balance()).isEqualTo(500_000L);
		assertThat(balances.get(walletId).version()).isEqualTo(2L);

		assertThat(projector.project(UUID.randomUUID(),
				new WalletDebited(walletId, Money.vnd(125_000), UUID.randomUUID(), "TRANSFER_CLEARING",
						AccountType.CLEARING),
				3L, t3)).isTrue();
		assertThat(balances.get(walletId).balance()).isEqualTo(375_000L);
		assertThat(balances.get(walletId).version()).isEqualTo(3L);

		assertThat(projector.project(UUID.randomUUID(), new WalletLocked(walletId, "compliance"), 4L, t4)).isTrue();
		assertThat(balances.get(walletId).status()).isEqualTo("LOCKED");
		assertThat(balances.get(walletId).version()).isEqualTo(4L);
	}

	@Test
	void duplicateEventIdIsIgnoredIdempotently() {
		UUID walletId = UUID.randomUUID();
		UUID ownerId = UUID.randomUUID();
		UUID creditEventId = UUID.randomUUID();
		Instant now = Instant.parse("2026-11-21T01:00:00Z");

		projector.project(UUID.randomUUID(), new WalletOpened(walletId, ownerId, Currency.VND), 1L, now);
		WalletCredited credited = new WalletCredited(walletId, Money.vnd(200_000), UUID.randomUUID(),
				"BANK_SETTLEMENT", AccountType.SETTLEMENT);

		assertThat(projector.project(creditEventId, credited, 2L, now)).isTrue();
		assertThat(projector.project(creditEventId, credited, 2L, now)).isFalse();
		assertThat(balances.get(walletId).balance()).isEqualTo(200_000L);
	}

	@Test
	void debitExceedingBalanceThrowsAndRollsBackProcessedEvent() {
		UUID walletId = UUID.randomUUID();
		Instant now = Instant.parse("2026-11-21T01:00:00Z");
		projector.project(UUID.randomUUID(), new WalletOpened(walletId, UUID.randomUUID(), Currency.VND), 1L, now);

		UUID debitEventId = UUID.randomUUID();
		assertThatThrownBy(() -> projector.project(debitEventId,
				new WalletDebited(walletId, Money.vnd(100_000), UUID.randomUUID(), "TRANSFER_CLEARING",
						AccountType.CLEARING),
				2L, now))
				.isInstanceOf(IllegalStateException.class);
		assertThat(balances.get(walletId).balance()).isZero();
		assertThat(processed).doesNotContain(BalanceProjector.DEFAULT_CONSUMER_GROUP + ":" + debitEventId);
	}

	@Test
	void rejectsUnsupportedEventOrInvalidVersionBeforeOpeningTransaction() {
		assertThatThrownBy(() -> projector.project(UUID.randomUUID(), new DepositConfirmed(UUID.randomUUID()), 1L,
				Instant.now()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> projector.project(UUID.randomUUID(),
				new WalletOpened(UUID.randomUUID(), UUID.randomUUID(), Currency.VND), 0L, Instant.now()))
				.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(tx);
	}
}
