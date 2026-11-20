package com.lakeserl.mini_banking.ledger.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.wallet.domain.events.WalletCredited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletDebited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletLocked;
import com.lakeserl.mini_banking.wallet.domain.events.WalletOpened;

class LedgerProjectorUnitTest {

	private static final Instant OCCURRED_AT = Instant.parse("2026-11-19T10:30:00Z");
	private static final OffsetDateTime EXPECTED_TS = OffsetDateTime.ofInstant(OCCURRED_AT, ZoneOffset.UTC);

	private JdbcTemplate jdbc;
	private RecordingTransactionManager txManager;
	private TransactionTemplate tx;
	private LedgerProjector projector;
	private LedgerQueryRepository query;

	@BeforeEach
	void setUp() {
		jdbc = mock(JdbcTemplate.class);
		txManager = new RecordingTransactionManager();
		tx = new TransactionTemplate(txManager);
		projector = new LedgerProjector(jdbc, tx);
		query = new LedgerQueryRepository(jdbc);
	}

	@Test
	void constructorValidatesArgumentsAndDefaultsConsumerGroup() {
		assertThat(LedgerProjector.DEFAULT_CONSUMER_GROUP).isEqualTo("ledger-projector");
		assertThatThrownBy(() -> new LedgerProjector(null, tx))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new LedgerProjector(jdbc, null))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new LedgerProjector(jdbc, tx, null))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new LedgerProjector(jdbc, tx, "   "))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LedgerQueryRepository(null))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void projectRejectsNullArgumentsBeforeOpeningTransaction() {
		UUID eventId = UUID.randomUUID();
		WalletOpened event = new WalletOpened(UUID.randomUUID(), UUID.randomUUID(), Currency.VND);

		assertThatThrownBy(() -> projector.project(null, event, OCCURRED_AT))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> projector.project(eventId, null, OCCURRED_AT))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> projector.project(eventId, event, null))
				.isInstanceOf(NullPointerException.class);
		assertThat(txManager.commitCount).isZero();
		assertThat(txManager.rollbackCount).isZero();
		verifyNoInteractions(jdbc);
	}

	@Test
	void projectDepositInsertsProcessedEventAndBalancedDebitSettlementCreditWalletInSingleTx() {
		UUID eventId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		UUID txId = UUID.randomUUID();
		when(jdbc.update(contains("processed_events"), eq("ledger-projector"), eq(eventId))).thenReturn(1);
		when(jdbc.update(contains("ledger_entries"), any(), any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(1);

		boolean applied = projector.project(
				eventId,
				new WalletCredited(walletId, Money.vnd(500_000L), txId, "BANK_SETTLEMENT", AccountType.SETTLEMENT),
				OCCURRED_AT);

		assertThat(applied).isTrue();
		assertThat(txManager.commitCount).isEqualTo(1);
		assertThat(txManager.rollbackCount).isZero();

		InOrder order = inOrder(jdbc);
		order.verify(jdbc).update(
				contains("ON CONFLICT (consumer_group, event_id) DO NOTHING"),
				eq("ledger-projector"),
				eq(eventId));
		order.verify(jdbc).update(
				contains("INSERT INTO ledger_entries"),
				eq(txId),
				eq("BANK_SETTLEMENT"),
				eq("SETTLEMENT"),
				eq("DEBIT"),
				eq(500_000L),
				eq("VND"),
				eq(EXPECTED_TS),
				eq(eventId));
		order.verify(jdbc).update(
				contains("INSERT INTO ledger_entries"),
				eq(txId),
				eq(walletId.toString()),
				eq("USER_WALLET"),
				eq("CREDIT"),
				eq(500_000L),
				eq("VND"),
				eq(EXPECTED_TS),
				eq(eventId));
	}

	@Test
	void projectWithdrawalAndReversalInsertOppositeBalancedDoubleEntryPairs() {
		UUID walletId = UUID.randomUUID();
		UUID withdrawalTxId = UUID.randomUUID();
		UUID debitEventId = UUID.randomUUID();
		UUID reversalEventId = UUID.randomUUID();
		when(jdbc.update(contains("processed_events"), eq("ledger-projector"), any(UUID.class))).thenReturn(1);
		when(jdbc.update(contains("ledger_entries"), any(), any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(1);

		boolean debited = projector.project(
				debitEventId,
				new WalletDebited(walletId, Money.vnd(250_000L), withdrawalTxId, "BANK_SETTLEMENT", AccountType.SETTLEMENT),
				OCCURRED_AT);
		boolean reversed = projector.project(
				reversalEventId,
				new WalletCredited(walletId, Money.vnd(250_000L), withdrawalTxId, "BANK_SETTLEMENT", AccountType.SETTLEMENT),
				OCCURRED_AT);

		assertThat(debited).isTrue();
		assertThat(reversed).isTrue();
		assertThat(txManager.commitCount).isEqualTo(2);

		InOrder order = inOrder(jdbc);
		order.verify(jdbc).update(contains("ledger_entries"),
				eq(withdrawalTxId), eq(walletId.toString()), eq("USER_WALLET"), eq("DEBIT"),
				eq(250_000L), eq("VND"), eq(EXPECTED_TS), eq(debitEventId));
		order.verify(jdbc).update(contains("ledger_entries"),
				eq(withdrawalTxId), eq("BANK_SETTLEMENT"), eq("SETTLEMENT"), eq("CREDIT"),
				eq(250_000L), eq("VND"), eq(EXPECTED_TS), eq(debitEventId));
		order.verify(jdbc).update(contains("ledger_entries"),
				eq(withdrawalTxId), eq("BANK_SETTLEMENT"), eq("SETTLEMENT"), eq("DEBIT"),
				eq(250_000L), eq("VND"), eq(EXPECTED_TS), eq(reversalEventId));
		order.verify(jdbc).update(contains("ledger_entries"),
				eq(withdrawalTxId), eq(walletId.toString()), eq("USER_WALLET"), eq("CREDIT"),
				eq(250_000L), eq("VND"), eq(EXPECTED_TS), eq(reversalEventId));
	}

	@Test
	void projectTransferDebitCreditAndCompensationUseClearingAccountWithExactSigns() {
		UUID sourceId = UUID.randomUUID();
		UUID destId = UUID.randomUUID();
		UUID transferId = UUID.randomUUID();
		UUID debitEventId = UUID.randomUUID();
		UUID creditEventId = UUID.randomUUID();
		UUID compEventId = UUID.randomUUID();
		LedgerProjector customGroupProjector = new LedgerProjector(jdbc, tx, "custom-ledger-group");

		when(jdbc.update(contains("processed_events"), eq("custom-ledger-group"), any(UUID.class))).thenReturn(1);
		when(jdbc.update(contains("ledger_entries"), any(), any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(1);

		assertThat(customGroupProjector.project(
				debitEventId,
				new WalletDebited(sourceId, Money.vnd(75_000L), transferId, "TRANSFER_CLEARING", AccountType.CLEARING),
				OCCURRED_AT)).isTrue();
		assertThat(customGroupProjector.project(
				creditEventId,
				new WalletCredited(destId, Money.vnd(75_000L), transferId, "TRANSFER_CLEARING", AccountType.CLEARING),
				OCCURRED_AT)).isTrue();
		assertThat(customGroupProjector.project(
				compEventId,
				new WalletCredited(sourceId, Money.vnd(75_000L), transferId, "TRANSFER_CLEARING", AccountType.CLEARING),
				OCCURRED_AT)).isTrue();

		InOrder order = inOrder(jdbc);
		order.verify(jdbc).update(contains("ledger_entries"),
				eq(transferId), eq(sourceId.toString()), eq("USER_WALLET"), eq("DEBIT"),
				eq(75_000L), eq("VND"), eq(EXPECTED_TS), eq(debitEventId));
		order.verify(jdbc).update(contains("ledger_entries"),
				eq(transferId), eq("TRANSFER_CLEARING"), eq("CLEARING"), eq("CREDIT"),
				eq(75_000L), eq("VND"), eq(EXPECTED_TS), eq(debitEventId));

		order.verify(jdbc).update(contains("ledger_entries"),
				eq(transferId), eq("TRANSFER_CLEARING"), eq("CLEARING"), eq("DEBIT"),
				eq(75_000L), eq("VND"), eq(EXPECTED_TS), eq(creditEventId));
		order.verify(jdbc).update(contains("ledger_entries"),
				eq(transferId), eq(destId.toString()), eq("USER_WALLET"), eq("CREDIT"),
				eq(75_000L), eq("VND"), eq(EXPECTED_TS), eq(creditEventId));

		order.verify(jdbc).update(contains("ledger_entries"),
				eq(transferId), eq("TRANSFER_CLEARING"), eq("CLEARING"), eq("DEBIT"),
				eq(75_000L), eq("VND"), eq(EXPECTED_TS), eq(compEventId));
		order.verify(jdbc).update(contains("ledger_entries"),
				eq(transferId), eq(sourceId.toString()), eq("USER_WALLET"), eq("CREDIT"),
				eq(75_000L), eq("VND"), eq(EXPECTED_TS), eq(compEventId));
	}

	@Test
	void projectDuplicateEventReturnsFalseAndSkipsLedgerInserts() {
		UUID eventId = UUID.randomUUID();
		when(jdbc.update(contains("processed_events"), eq("ledger-projector"), eq(eventId))).thenReturn(0);

		boolean applied = projector.project(
				eventId,
				new WalletCredited(UUID.randomUUID(), Money.vnd(10_000L), UUID.randomUUID(),
						"BANK_SETTLEMENT", AccountType.SETTLEMENT),
				OCCURRED_AT);

		assertThat(applied).isFalse();
		assertThat(txManager.commitCount).isEqualTo(1);
		assertThat(txManager.rollbackCount).isZero();
		verify(jdbc, never()).update(contains("ledger_entries"), any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void projectWalletOpenedAndWalletLockedRecordProcessedEventAndInsertZeroLedgerRows() {
		UUID openedId = UUID.randomUUID();
		UUID lockedId = UUID.randomUUID();
		when(jdbc.update(contains("processed_events"), eq("ledger-projector"), any(UUID.class))).thenReturn(1);

		boolean opened = projector.project(
				openedId,
				new WalletOpened(UUID.randomUUID(), UUID.randomUUID(), Currency.VND),
				OCCURRED_AT);
		boolean locked = projector.project(
				lockedId,
				new WalletLocked(UUID.randomUUID(), "fraud review"),
				OCCURRED_AT);

		assertThat(opened).isTrue();
		assertThat(locked).isTrue();
		assertThat(txManager.commitCount).isEqualTo(2);
		assertThat(txManager.rollbackCount).isZero();
		verify(jdbc).update(contains("processed_events"), eq("ledger-projector"), eq(openedId));
		verify(jdbc).update(contains("processed_events"), eq("ledger-projector"), eq(lockedId));
		verify(jdbc, never()).update(contains("ledger_entries"), any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void projectUnknownDomainEventThrowsAndRollsBackTransaction() {
		record UnsupportedEvent(UUID id) implements DomainEvent {
		}
		UUID eventId = UUID.randomUUID();

		assertThatThrownBy(() -> projector.project(eventId, new UnsupportedEvent(UUID.randomUUID()), OCCURRED_AT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unsupported ledger event")
				.hasMessageContaining("UnsupportedEvent");

		assertThat(txManager.commitCount).isZero();
		assertThat(txManager.rollbackCount).isEqualTo(1);
		verifyNoInteractions(jdbc);
	}

	@Test
	void projectRejectsNonPositiveOrNonVndMonetaryEventsAndRollsBack() {
		UUID walletId = UUID.randomUUID();
		UUID txId = UUID.randomUUID();

		assertThatThrownBy(() -> projector.project(
				UUID.randomUUID(),
				new WalletCredited(walletId, Money.vnd(0L), txId, "BANK_SETTLEMENT", AccountType.SETTLEMENT),
				OCCURRED_AT))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> projector.project(
				UUID.randomUUID(),
				new WalletDebited(walletId, Money.vnd(-100L), txId, "BANK_SETTLEMENT", AccountType.SETTLEMENT),
				OCCURRED_AT))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> projector.project(
				UUID.randomUUID(),
				new WalletCredited(walletId, new Money(100L, Currency.USD), txId, "BANK_SETTLEMENT", AccountType.SETTLEMENT),
				OCCURRED_AT))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> projector.project(
				UUID.randomUUID(),
				new WalletDebited(walletId, Money.vnd(100L), txId, "  ", AccountType.SETTLEMENT),
				OCCURRED_AT))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(txManager.commitCount).isZero();
		assertThat(txManager.rollbackCount).isEqualTo(4);
		verifyNoInteractions(jdbc);
	}

	@Test
	void projectRollsBackTransactionIfLedgerInsertFails() {
		UUID eventId = UUID.randomUUID();
		when(jdbc.update(contains("processed_events"), eq("ledger-projector"), eq(eventId))).thenReturn(1);
		when(jdbc.update(contains("ledger_entries"), any(), any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(1)
				.thenThrow(new IllegalStateException("simulated db error"));

		assertThatThrownBy(() -> projector.project(
				eventId,
				new WalletCredited(UUID.randomUUID(), Money.vnd(50_000L), UUID.randomUUID(),
						"BANK_SETTLEMENT", AccountType.SETTLEMENT),
				OCCURRED_AT))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("simulated db error");

		assertThat(txManager.commitCount).isZero();
		assertThat(txManager.rollbackCount).isEqualTo(1);
	}

	@Test
	void queryRepositoryUsesCoalesceForAggregatesAndHandlesNullSafely() {
		ArgumentCaptor<String> totalSql = ArgumentCaptor.forClass(String.class);
		when(jdbc.queryForObject(totalSql.capture(), eq(Long.class))).thenReturn(null).thenReturn(12_345L);

		assertThat(query.totalDebitMinusCredit()).isZero();
		assertThat(query.totalDebitMinusCredit()).isEqualTo(12_345L);
		assertThat(totalSql.getValue())
				.contains("COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END), 0)")
				.contains("FROM ledger_entries");

		ArgumentCaptor<String> creditMinusDebitSql = ArgumentCaptor.forClass(String.class);
		when(jdbc.queryForObject(creditMinusDebitSql.capture(), eq(Long.class), eq("wallet-1")))
				.thenReturn(null)
				.thenReturn(500_000L);

		assertThat(query.accountNetCreditMinusDebit("wallet-1")).isZero();
		assertThat(query.accountNetCreditMinusDebit("wallet-1")).isEqualTo(500_000L);
		assertThat(creditMinusDebitSql.getValue())
				.contains("COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END), 0)")
				.contains("WHERE account_id = ?");

		ArgumentCaptor<String> debitMinusCreditSql = ArgumentCaptor.forClass(String.class);
		when(jdbc.queryForObject(debitMinusCreditSql.capture(), eq(Long.class), eq("BANK_SETTLEMENT")))
				.thenReturn(null)
				.thenReturn(250_000L);

		assertThat(query.accountNetDebitMinusCredit("BANK_SETTLEMENT")).isZero();
		assertThat(query.accountNetDebitMinusCredit("BANK_SETTLEMENT")).isEqualTo(250_000L);
		assertThat(debitMinusCreditSql.getValue())
				.contains("COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END), 0)")
				.contains("WHERE account_id = ?");

		assertThatThrownBy(() -> query.accountNetCreditMinusDebit(null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> query.accountNetCreditMinusDebit(" ")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> query.accountNetDebitMinusCredit(null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> query.accountNetDebitMinusCredit("")).isInstanceOf(IllegalArgumentException.class);
	}

	@SuppressWarnings("unchecked")
	@Test
	void unbalancedTransactionsQueriesGroupedImbalancesAndMapsUuid() throws Exception {
		UUID txId = UUID.randomUUID();
		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<RowMapper<UUID>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
		when(jdbc.query(sqlCaptor.capture(), mapperCaptor.capture())).thenReturn(List.of(txId));

		List<UUID> result = query.unbalancedTransactions();

		assertThat(result).containsExactly(txId);
		assertThat(sqlCaptor.getValue())
				.contains("SELECT transaction_id")
				.contains("FROM ledger_entries")
				.contains("GROUP BY transaction_id")
				.contains("HAVING SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END) <> 0")
				.contains("ORDER BY transaction_id");

		ResultSet rs = mock(ResultSet.class);
		when(rs.getObject("transaction_id", UUID.class)).thenReturn(txId);
		assertThat(mapperCaptor.getValue().mapRow(rs, 0)).isEqualTo(txId);
	}

	@Test
	void springContextWiresLedgerProjectorWithDefaultAndConfiguredConsumerGroup() {
		JdbcTemplate defaultJdbc = mock(JdbcTemplate.class);
		UUID defaultEventId = UUID.randomUUID();
		when(defaultJdbc.update(contains("processed_events"), eq("ledger-projector"), eq(defaultEventId))).thenReturn(1);

		new ApplicationContextRunner()
				.withBean(JdbcTemplate.class, () -> defaultJdbc)
				.withBean(TransactionTemplate.class, () -> new TransactionTemplate(new RecordingTransactionManager()))
				.withUserConfiguration(LedgerProjector.class, LedgerQueryRepository.class)
				.run(context -> {
					assertThat(context).hasSingleBean(LedgerProjector.class);
					assertThat(context).hasSingleBean(LedgerQueryRepository.class);
					boolean applied = context.getBean(LedgerProjector.class).project(
							defaultEventId,
							new WalletOpened(UUID.randomUUID(), UUID.randomUUID(), Currency.VND),
							OCCURRED_AT);
					assertThat(applied).isTrue();
					verify(defaultJdbc).update(contains("processed_events"), eq("ledger-projector"), eq(defaultEventId));
				});

		JdbcTemplate customJdbc = mock(JdbcTemplate.class);
		UUID customEventId = UUID.randomUUID();
		when(customJdbc.update(contains("processed_events"), eq("ledger-custom-group"), eq(customEventId))).thenReturn(1);

		new ApplicationContextRunner()
				.withPropertyValues("ledger.consumer.group-id=ledger-custom-group")
				.withBean(JdbcTemplate.class, () -> customJdbc)
				.withBean(TransactionTemplate.class, () -> new TransactionTemplate(new RecordingTransactionManager()))
				.withUserConfiguration(LedgerProjector.class, LedgerQueryRepository.class)
				.run(context -> {
					assertThat(context).hasSingleBean(LedgerProjector.class);
					boolean applied = context.getBean(LedgerProjector.class).project(
							customEventId,
							new WalletOpened(UUID.randomUUID(), UUID.randomUUID(), Currency.VND),
							OCCURRED_AT);
					assertThat(applied).isTrue();
					verify(customJdbc).update(contains("processed_events"), eq("ledger-custom-group"), eq(customEventId));
				});
	}

	private static final class RecordingTransactionManager implements PlatformTransactionManager {
		int activeDepth = 0;
		int commitCount = 0;
		int rollbackCount = 0;

		@Override
		public TransactionStatus getTransaction(TransactionDefinition definition) {
			activeDepth++;
			return new SimpleTransactionStatus();
		}

		@Override
		public void commit(TransactionStatus status) {
			activeDepth--;
			commitCount++;
		}

		@Override
		public void rollback(TransactionStatus status) {
			activeDepth--;
			rollbackCount++;
		}
	}
}
