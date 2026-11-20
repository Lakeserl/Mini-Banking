package com.lakeserl.mini_banking.ledger.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.wallet.domain.events.WalletCredited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletDebited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletLocked;
import com.lakeserl.mini_banking.wallet.domain.events.WalletOpened;

@PostgresDataTest
@Import({ LedgerProjector.class, LedgerQueryRepository.class })
class LedgerProjectorTest {

	private static final Instant OCCURRED_AT = Instant.parse("2026-11-19T12:00:00Z");

	@Autowired
	LedgerProjector projector;

	@Autowired
	LedgerQueryRepository query;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void clean() {
		jdbc.update("DELETE FROM ledger_entries");
		jdbc.update("DELETE FROM processed_events");
	}

	@Test
	void emptyTableQueriesReturnZeroWithoutNullPointerException() {
		assertThat(query.totalDebitMinusCredit()).isZero();
		assertThat(query.accountNetCreditMinusDebit(UUID.randomUUID().toString())).isZero();
		assertThat(query.accountNetDebitMinusCredit("BANK_SETTLEMENT")).isZero();
		assertThat(query.unbalancedTransactions()).isEmpty();
	}

	@Test
	void depositProjectsDebitBankSettlementAndCreditWalletBalanced() {
		UUID walletId = UUID.randomUUID();
		UUID txId = UUID.randomUUID();
		UUID eventId = UUID.randomUUID();

		boolean applied = projector.project(
				eventId,
				new WalletCredited(walletId, Money.vnd(500_000L), txId, "BANK_SETTLEMENT", AccountType.SETTLEMENT),
				OCCURRED_AT);

		assertThat(applied).isTrue();
		assertThat(ledgerEntryCount()).isEqualTo(2);
		assertThat(query.totalDebitMinusCredit()).isZero();
		assertThat(query.accountNetCreditMinusDebit(walletId.toString())).isEqualTo(500_000L);
		assertThat(query.accountNetDebitMinusCredit("BANK_SETTLEMENT")).isEqualTo(500_000L);
		assertThat(query.unbalancedTransactions()).isEmpty();

		List<String> legs = jdbc.query(
				"SELECT account_id || ':' || account_type || ':' || entry_type || ':' || amount || ':' || currency "
						+ "FROM ledger_entries WHERE event_id = ? ORDER BY entry_type",
				(rs, rowNum) -> rs.getString(1),
				eventId);
		assertThat(legs).containsExactly(
				walletId + ":USER_WALLET:CREDIT:500000:VND",
				"BANK_SETTLEMENT:SETTLEMENT:DEBIT:500000:VND");
	}

	@Test
	void withdrawalAndFailureReversalYieldFourEntriesAndZeroNetBalances() {
		UUID walletId = UUID.randomUUID();
		UUID withdrawalTxId = UUID.randomUUID();

		boolean debited = projector.project(
				UUID.randomUUID(),
				new WalletDebited(walletId, Money.vnd(250_000L), withdrawalTxId, "BANK_SETTLEMENT", AccountType.SETTLEMENT),
				OCCURRED_AT);
		boolean reversed = projector.project(
				UUID.randomUUID(),
				new WalletCredited(walletId, Money.vnd(250_000L), withdrawalTxId, "BANK_SETTLEMENT", AccountType.SETTLEMENT),
				OCCURRED_AT.plusSeconds(5));

		assertThat(debited).isTrue();
		assertThat(reversed).isTrue();
		assertThat(ledgerEntryCount()).isEqualTo(4);
		assertThat(query.accountNetCreditMinusDebit(walletId.toString())).isZero();
		assertThat(query.accountNetDebitMinusCredit("BANK_SETTLEMENT")).isZero();
		assertThat(query.totalDebitMinusCredit()).isZero();
		assertThat(query.unbalancedTransactions()).isEmpty();
	}

	@Test
	void transferShowsClearingBalanceMidSagaAndZeroAfterCreditLeg() {
		UUID sourceWalletId = UUID.randomUUID();
		UUID destWalletId = UUID.randomUUID();
		UUID transferId = UUID.randomUUID();
		long amount = 1_000_000L;

		boolean debitApplied = projector.project(
				UUID.randomUUID(),
				new WalletDebited(sourceWalletId, Money.vnd(amount), transferId, "TRANSFER_CLEARING", AccountType.CLEARING),
				OCCURRED_AT);

		assertThat(debitApplied).isTrue();
		assertThat(query.accountNetCreditMinusDebit("TRANSFER_CLEARING")).isEqualTo(amount);
		assertThat(query.accountNetCreditMinusDebit(sourceWalletId.toString())).isEqualTo(-amount);
		assertThat(query.totalDebitMinusCredit()).isZero();
		assertThat(query.unbalancedTransactions()).isEmpty();

		boolean creditApplied = projector.project(
				UUID.randomUUID(),
				new WalletCredited(destWalletId, Money.vnd(amount), transferId, "TRANSFER_CLEARING", AccountType.CLEARING),
				OCCURRED_AT.plusSeconds(1));

		assertThat(creditApplied).isTrue();
		assertThat(ledgerEntryCount()).isEqualTo(4);
		assertThat(query.accountNetCreditMinusDebit("TRANSFER_CLEARING")).isZero();
		assertThat(query.accountNetDebitMinusCredit("TRANSFER_CLEARING")).isZero();
		assertThat(query.accountNetCreditMinusDebit(sourceWalletId.toString())).isEqualTo(-amount);
		assertThat(query.accountNetCreditMinusDebit(destWalletId.toString())).isEqualTo(amount);
		assertThat(query.totalDebitMinusCredit()).isZero();
		assertThat(query.unbalancedTransactions()).isEmpty();
	}

	@Test
	void transferCompensationReturnsClearingAndSourceWalletToZero() {
		UUID sourceWalletId = UUID.randomUUID();
		UUID transferId = UUID.randomUUID();
		long amount = 300_000L;

		projector.project(
				UUID.randomUUID(),
				new WalletDebited(sourceWalletId, Money.vnd(amount), transferId, "TRANSFER_CLEARING", AccountType.CLEARING),
				OCCURRED_AT);
		projector.project(
				UUID.randomUUID(),
				new WalletCredited(sourceWalletId, Money.vnd(amount), transferId, "TRANSFER_CLEARING", AccountType.CLEARING),
				OCCURRED_AT.plusSeconds(2));

		assertThat(ledgerEntryCount()).isEqualTo(4);
		assertThat(query.accountNetCreditMinusDebit("TRANSFER_CLEARING")).isZero();
		assertThat(query.accountNetCreditMinusDebit(sourceWalletId.toString())).isZero();
		assertThat(query.totalDebitMinusCredit()).isZero();
		assertThat(query.unbalancedTransactions()).isEmpty();
	}

	@Test
	void duplicateEventIdReturnsFalseAndInsertsNoExtraRows() {
		UUID walletId = UUID.randomUUID();
		UUID txId = UUID.randomUUID();
		UUID eventId = UUID.randomUUID();
		WalletCredited event = new WalletCredited(
				walletId, Money.vnd(100_000L), txId, "BANK_SETTLEMENT", AccountType.SETTLEMENT);

		boolean first = projector.project(eventId, event, OCCURRED_AT);
		boolean second = projector.project(eventId, event, OCCURRED_AT);

		assertThat(first).isTrue();
		assertThat(second).isFalse();
		assertThat(ledgerEntryCount()).isEqualTo(2);
		assertThat(processedEventCount()).isEqualTo(1);
		assertThat(query.accountNetCreditMinusDebit(walletId.toString())).isEqualTo(100_000L);
	}

	@Test
	void nonMonetaryWalletEventsInsertZeroLedgerRowsAndUnknownEventThrowsWithoutCommittingProcessedEvents() {
		UUID walletId = UUID.randomUUID();
		UUID openedEventId = UUID.randomUUID();
		UUID lockedEventId = UUID.randomUUID();

		boolean openedApplied = projector.project(
				openedEventId,
				new WalletOpened(walletId, UUID.randomUUID(), Currency.VND),
				OCCURRED_AT);
		boolean lockedApplied = projector.project(
				lockedEventId,
				new WalletLocked(walletId, "compliance hold"),
				OCCURRED_AT.plusSeconds(1));
		boolean openedDuplicate = projector.project(
				openedEventId,
				new WalletOpened(walletId, UUID.randomUUID(), Currency.VND),
				OCCURRED_AT);

		assertThat(openedApplied).isTrue();
		assertThat(lockedApplied).isTrue();
		assertThat(openedDuplicate).isFalse();
		assertThat(ledgerEntryCount()).isZero();
		assertThat(processedEventCount()).isEqualTo(2);

		UUID unknownEventId = UUID.randomUUID();
		record UnknownTestEvent(UUID id) implements DomainEvent {
		}

		assertThatThrownBy(() -> projector.project(unknownEventId, new UnknownTestEvent(UUID.randomUUID()), OCCURRED_AT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unsupported ledger event");

		Integer unknownProcessed = jdbc.queryForObject(
				"SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Integer.class, unknownEventId);
		assertThat(unknownProcessed).isZero();
		assertThat(ledgerEntryCount()).isZero();
	}

	@Test
	void unbalancedTransactionsDetectsCorruptedSingleLegTransaction() {
		UUID unbalancedTxId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO ledger_entries
					(transaction_id, account_id, account_type, entry_type, amount, currency, occurred_at, event_id)
				VALUES (?, 'BANK_SETTLEMENT', 'SETTLEMENT', 'DEBIT', 50000, 'VND', now(), ?)
				""", unbalancedTxId, UUID.randomUUID());

		List<UUID> unbalanced = query.unbalancedTransactions();

		assertThat(unbalanced).containsExactly(unbalancedTxId);
		assertThat(query.totalDebitMinusCredit()).isEqualTo(50_000L);
	}

	private int ledgerEntryCount() {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries", Integer.class);
		return count != null ? count : 0;
	}

	private int processedEventCount() {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM processed_events", Integer.class);
		return count != null ? count : 0;
	}
}
