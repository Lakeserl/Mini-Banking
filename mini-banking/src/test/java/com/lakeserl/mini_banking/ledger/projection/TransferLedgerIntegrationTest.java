package com.lakeserl.mini_banking.ledger.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.deposit.command.DepositCommandHandler;
import com.lakeserl.mini_banking.deposit.domain.DepositStatus;
import com.lakeserl.mini_banking.mockbank.InMemoryMockBankGateway;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.PayoutStatus;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.VerificationStatus;
import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.EventSerializer;
import com.lakeserl.mini_banking.transfer.domain.Transfer;
import com.lakeserl.mini_banking.transfer.domain.TransferStatus;
import com.lakeserl.mini_banking.transfer.domain.TransferType;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferRepository;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferSagaStateStore;
import com.lakeserl.mini_banking.transfer.saga.TransferSagaOrchestrator;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.domain.WalletStatus;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;
import com.lakeserl.mini_banking.withdrawal.command.WithdrawalCommandHandler;
import com.lakeserl.mini_banking.withdrawal.domain.WithdrawalStatus;

@PostgresDataTest
@Import({ LedgerProjector.class, LedgerQueryRepository.class })
class TransferLedgerIntegrationTest {

	private static final String BANK_SETTLEMENT = DepositCommandHandler.BANK_SETTLEMENT_ACCOUNT;
	private static final String TRANSFER_CLEARING = TransferSagaOrchestrator.TRANSFER_CLEARING_ACCOUNT;

	@Autowired
	WalletCommandHandler walletHandler;

	@Autowired
	DepositCommandHandler depositHandler;

	@Autowired
	WithdrawalCommandHandler withdrawalHandler;

	@Autowired
	TransferSagaOrchestrator orchestrator;

	@Autowired
	TransferRepository transferRepository;

	@Autowired
	TransferSagaStateStore sagaStateStore;

	@Autowired
	WalletRepository walletRepository;

	@Autowired
	EventSerializer eventSerializer;

	@Autowired
	LedgerProjector ledgerProjector;

	@Autowired
	LedgerQueryRepository ledgerQuery;

	@Autowired
	InMemoryMockBankGateway mockBank;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate tx;

	record WalletOutboxRow(long id, UUID eventId, String eventType, String payload, Instant createdAt) {
	}

	@BeforeEach
	void cleanProjectionAndOutbox() {
		jdbc.update("DELETE FROM ledger_entries");
		jdbc.update("DELETE FROM processed_events");
		jdbc.update("DELETE FROM outbox");
		mockBank.reset();
	}

	@Test
	void fullLifecycleOutboxToLedgerProjectionPreservesInvariantsIdempotencyAndTimestamps() {
		// 1. Open 3 wallets: A, B, C
		UUID walletA = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		UUID walletB = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		UUID walletC = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);

		// 2. Confirmed deposit (+5,000,000) and rejected deposit into A
		assertThat(depositHandler.deposit(UUID.randomUUID(), walletA, Money.vnd(5_000_000L), "DEP-OK-A"))
				.isEqualTo(DepositStatus.CONFIRMED);
		mockBank.presetDeposit("DEP-REJ-A", VerificationStatus.REJECTED);
		assertThat(depositHandler.deposit(UUID.randomUUID(), walletA, Money.vnd(900_000L), "DEP-REJ-A"))
				.isEqualTo(DepositStatus.FAILED);

		// 3. Confirmed withdrawal (-1,000,000) and rejected/reversed withdrawal (-500,000 then +500,000) on A
		assertThat(withdrawalHandler.withdraw(UUID.randomUUID(), walletA, Money.vnd(1_000_000L), "970436-1001"))
				.isEqualTo(WithdrawalStatus.CONFIRMED);
		UUID failedWithdrawalId = UUID.randomUUID();
		mockBank.presetPayout(failedWithdrawalId, PayoutStatus.REJECTED);
		assertThat(withdrawalHandler.withdraw(failedWithdrawalId, walletA, Money.vnd(500_000L), "970436-1002"))
				.isEqualTo(WithdrawalStatus.FAILED);

		// 4. Two completed transfers A -> B (1,200,000 and 600,000)
		UUID transferAbToRefundId = UUID.randomUUID();
		Transfer transferAbToRefund = orchestrator.transfer(transferAbToRefundId, walletA, walletB,
				Money.vnd(1_200_000L));
		assertThat(transferAbToRefund.status()).isEqualTo(TransferStatus.COMPLETED);

		UUID transferAbKeptId = UUID.randomUUID();
		Transfer transferAbKept = orchestrator.transfer(transferAbKeptId, walletA, walletB, Money.vnd(600_000L));
		assertThat(transferAbKept.status()).isEqualTo(TransferStatus.COMPLETED);

		// 5. Refund of the first completed transfer A -> B (reversing B -> A for 1,200,000)
		UUID refundId = UUID.randomUUID();
		Transfer refund = orchestrator.refund(refundId, transferAbToRefundId);
		assertThat(refund.status()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(refund.type()).isEqualTo(TransferType.REFUND);
		assertThat(refund.originalTransferId()).isEqualTo(transferAbToRefundId);

		// 6. Compensated transfer A -> C (700,000): destination C locked after debit AND source A also locked
		// before compensation so creditCompensation on a locked wallet is exercised end-to-end.
		UUID compensatedTransferId = UUID.randomUUID();
		seedDebitedTransfer(compensatedTransferId, walletA, walletC, Money.vnd(700_000L));

		walletHandler.lockWallet(walletC, "destination frozen mid-saga");
		walletHandler.lockWallet(walletA, "source also locked before compensation");

		Transfer resumed = orchestrator.resume(compensatedTransferId, 0, 3);
		assertThat(resumed.status()).isEqualTo(TransferStatus.REVERSED);
		assertThat(walletRepository.load(walletA)).get().extracting(Wallet::status).isEqualTo(WalletStatus.LOCKED);
		assertThat(walletRepository.load(walletC)).get().extracting(Wallet::status).isEqualTo(WalletStatus.LOCKED);

		// 7. Drain ALL Wallet outbox rows in id order into LedgerProjector (Pass 1)
		// Expected Wallet events (17): 3 WalletOpened + 1 deposit + 1 withdrawal + 2 reversed withdrawal
		// + 2 transfer1 + 2 transfer2 + 2 refund + 1 comp debit + 2 WalletLocked + 1 comp credit.
		List<WalletOutboxRow> outboxRows = loadWalletOutboxRows();
		assertThat(outboxRows).hasSize(17);

		for (WalletOutboxRow row : outboxRows) {
			DomainEvent event = eventSerializer.deserialize(row.eventType(), row.payload());
			boolean applied = ledgerProjector.project(row.eventId(), event, row.createdAt());
			assertThat(applied).as("first pass projection of event %s (%s)", row.eventId(), row.eventType()).isTrue();

			// Accounting invariants hold after every single projected event (including mid-saga DEBITED).
			assertThat(ledgerQuery.totalDebitMinusCredit()).isZero();
			assertThat(ledgerQuery.unbalancedTransactions()).isEmpty();
			long walletsSum = ledgerQuery.accountNetCreditMinusDebit(walletA.toString())
					+ ledgerQuery.accountNetCreditMinusDebit(walletB.toString())
					+ ledgerQuery.accountNetCreditMinusDebit(walletC.toString());
			long clearingNet = ledgerQuery.accountNetCreditMinusDebit(TRANSFER_CLEARING);
			assertThat(ledgerQuery.accountNetDebitMinusCredit(BANK_SETTLEMENT)).isEqualTo(walletsSum + clearingNet);
		}

		int ledgerCountAfterFirstPass = ledgerEntryCount();
		int processedCountAfterFirstPass = processedEventCount();
		// 12 monetary Wallet events * 2 double-entry legs = 24 ledger rows; 5 lifecycle events (3 opened + 2 locked) = 0 rows.
		assertThat(ledgerCountAfterFirstPass).isEqualTo(24);
		assertThat(processedCountAfterFirstPass).isEqualTo(17);

		// Verify outbox.created_at was preserved verbatim into ledger_entries.occurred_at
		Integer mismatchedTimestamps = jdbc.queryForObject("""
				SELECT COUNT(*)
				FROM ledger_entries le
				JOIN outbox o ON o.event_id = le.event_id
				WHERE le.occurred_at <> o.created_at
				""", Integer.class);
		Integer matchedTimestamps = jdbc.queryForObject("""
				SELECT COUNT(*)
				FROM ledger_entries le
				JOIN outbox o ON o.event_id = le.event_id
				WHERE le.occurred_at = o.created_at
				""", Integer.class);
		assertThat(mismatchedTimestamps).isZero();
		assertThat(matchedTimestamps).isEqualTo(ledgerCountAfterFirstPass);

		// 8. Drain ALL Wallet outbox rows a SECOND time to prove full idempotency
		for (WalletOutboxRow row : outboxRows) {
			DomainEvent event = eventSerializer.deserialize(row.eventType(), row.payload());
			boolean applied = ledgerProjector.project(row.eventId(), event, row.createdAt());
			assertThat(applied).as("second pass redelivery of event %s must be ignored", row.eventId()).isFalse();
		}
		assertThat(ledgerEntryCount()).isEqualTo(ledgerCountAfterFirstPass);
		assertThat(processedEventCount()).isEqualTo(processedCountAfterFirstPass);

		// 9. Assert final terminal accounting invariants and exact match with replayed Wallet aggregates
		long expectedA = walletRepository.load(walletA).orElseThrow().balance().amount();
		long expectedB = walletRepository.load(walletB).orElseThrow().balance().amount();
		long expectedC = walletRepository.load(walletC).orElseThrow().balance().amount();
		assertThat(expectedA).isEqualTo(3_400_000L);
		assertThat(expectedB).isEqualTo(600_000L);
		assertThat(expectedC).isZero();

		assertThat(ledgerQuery.totalDebitMinusCredit()).isZero();
		assertThat(ledgerQuery.unbalancedTransactions()).isEmpty();
		assertThat(ledgerQuery.accountNetCreditMinusDebit(TRANSFER_CLEARING)).isZero();
		assertThat(ledgerQuery.accountNetDebitMinusCredit(TRANSFER_CLEARING)).isZero();
		assertThat(ledgerQuery.accountNetDebitMinusCredit(BANK_SETTLEMENT))
				.isEqualTo(expectedA + expectedB + expectedC);
		assertThat(ledgerQuery.accountNetCreditMinusDebit(walletA.toString())).isEqualTo(expectedA);
		assertThat(ledgerQuery.accountNetCreditMinusDebit(walletB.toString())).isEqualTo(expectedB);
		assertThat(ledgerQuery.accountNetCreditMinusDebit(walletC.toString())).isEqualTo(expectedC);
	}

	@Test
	void midSagaDebitedStateHoldsClearingBalanceAndZeroesUponCompletion() {
		UUID walletA = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		UUID walletB = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		assertThat(depositHandler.deposit(UUID.randomUUID(), walletA, Money.vnd(1_000_000L), "DEP-MID-A"))
				.isEqualTo(DepositStatus.CONFIRMED);

		UUID transferId = UUID.randomUUID();
		seedDebitedTransfer(transferId, walletA, walletB, Money.vnd(400_000L));

		// Drain outbox while transfer is mid-saga (DEBITED)
		for (WalletOutboxRow row : loadWalletOutboxRows()) {
			DomainEvent event = eventSerializer.deserialize(row.eventType(), row.payload());
			ledgerProjector.project(row.eventId(), event, row.createdAt());
		}

		assertThat(ledgerQuery.totalDebitMinusCredit()).isZero();
		assertThat(ledgerQuery.unbalancedTransactions()).isEmpty();
		assertThat(ledgerQuery.accountNetCreditMinusDebit(TRANSFER_CLEARING)).isEqualTo(400_000L);
		assertThat(ledgerQuery.accountNetCreditMinusDebit(walletA.toString())).isEqualTo(600_000L);
		assertThat(ledgerQuery.accountNetCreditMinusDebit(walletB.toString())).isZero();
		assertThat(ledgerQuery.accountNetDebitMinusCredit(BANK_SETTLEMENT)).isEqualTo(1_000_000L);

		// Resume transfer to COMPLETED and drain full outbox again (existing rows idempotently skipped)
		Transfer completed = orchestrator.resume(transferId, 0, 3);
		assertThat(completed.status()).isEqualTo(TransferStatus.COMPLETED);

		int newlyApplied = 0;
		for (WalletOutboxRow row : loadWalletOutboxRows()) {
			DomainEvent event = eventSerializer.deserialize(row.eventType(), row.payload());
			if (ledgerProjector.project(row.eventId(), event, row.createdAt())) {
				newlyApplied++;
			}
		}
		assertThat(newlyApplied).isEqualTo(1);
		assertThat(ledgerQuery.totalDebitMinusCredit()).isZero();
		assertThat(ledgerQuery.unbalancedTransactions()).isEmpty();
		assertThat(ledgerQuery.accountNetCreditMinusDebit(TRANSFER_CLEARING)).isZero();
		assertThat(ledgerQuery.accountNetCreditMinusDebit(walletA.toString()))
				.isEqualTo(walletRepository.load(walletA).orElseThrow().balance().amount())
				.isEqualTo(600_000L);
		assertThat(ledgerQuery.accountNetCreditMinusDebit(walletB.toString()))
				.isEqualTo(walletRepository.load(walletB).orElseThrow().balance().amount())
				.isEqualTo(400_000L);
		assertThat(ledgerQuery.accountNetDebitMinusCredit(BANK_SETTLEMENT)).isEqualTo(1_000_000L);
	}

	private void seedDebitedTransfer(UUID transferId, UUID sourceWalletId, UUID destWalletId, Money amount) {
		tx.executeWithoutResult(status -> {
			sagaStateStore.insertInitiated(transferId, sourceWalletId, destWalletId, amount,
					TransferType.STANDARD, null, TransferSagaOrchestrator.MAX_DAILY_OUTGOING_VND);
			Transfer initiated = Transfer.initiate(transferId, sourceWalletId, destWalletId, amount,
					TransferType.STANDARD, null);
			transferRepository.save(initiated);
		});
		walletHandler.debit(sourceWalletId, transferId, amount, TRANSFER_CLEARING, AccountType.CLEARING);
		tx.executeWithoutResult(status -> {
			Transfer latest = transferRepository.load(transferId).orElseThrow();
			latest.markDebitCompleted();
			transferRepository.save(latest);
			sagaStateStore.updateStatus(transferId, TransferStatus.DEBITED, null);
		});
	}

	private List<WalletOutboxRow> loadWalletOutboxRows() {
		return jdbc.query("""
				SELECT id, event_id, event_type, payload::text AS payload, created_at
				FROM outbox
				WHERE aggregate_type = 'Wallet'
				ORDER BY id
				""",
				(rs, rowNum) -> new WalletOutboxRow(
						rs.getLong("id"),
						rs.getObject("event_id", UUID.class),
						rs.getString("event_type"),
						rs.getString("payload"),
						rs.getObject("created_at", OffsetDateTime.class).toInstant()));
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
