package com.lakeserl.mini_banking.transfer.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.domain.PendingEvent;
import com.lakeserl.mini_banking.shared.eventsourcing.DuplicateEventException;
import com.lakeserl.mini_banking.shared.eventsourcing.EventSerializer;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.shared.eventsourcing.OptimisticConcurrencyException;
import com.lakeserl.mini_banking.shared.eventsourcing.PostgresSnapshotStore;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;
import com.lakeserl.mini_banking.transfer.domain.Transfer;
import com.lakeserl.mini_banking.transfer.domain.TransferStatus;
import com.lakeserl.mini_banking.transfer.domain.TransferType;
import com.lakeserl.mini_banking.transfer.domain.exceptions.InvalidTransferStateException;
import com.lakeserl.mini_banking.transfer.domain.exceptions.TransferNotFoundException;
import com.lakeserl.mini_banking.transfer.domain.exceptions.TransferPolicyViolationException;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferEventTypes;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferRepository;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferSagaStateStore;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.domain.events.WalletCredited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletDebited;
import com.lakeserl.mini_banking.wallet.domain.exceptions.ConflictingTransactionException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InvalidAmountException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletLockedException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletNotFoundException;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletEventTypes;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;

import static org.mockito.Mockito.mock;

class TransferSagaOrchestratorUnitTest {

	private static final Instant FIXED_NOW = Instant.parse("2026-11-19T08:00:00Z");
	private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

	private InMemoryEventStore eventStore;
	private InMemorySagaStateStore sagaStateStore;
	private WalletRepository walletRepository;
	private WalletCommandHandler walletHandler;
	private TransferRepository transferRepository;
	private RecordingTransactionManager txManager;
	private TransactionTemplate txTemplate;
	private TransferSagaOrchestrator orchestrator;

	@BeforeEach
	void setUp() {
		eventStore = new InMemoryEventStore();
		sagaStateStore = new InMemorySagaStateStore(FIXED_CLOCK);
		EventSerializer serializer = new EventSerializer(List.of(new WalletEventTypes(), new TransferEventTypes()));
		PostgresSnapshotStore snapshotStore = new PostgresSnapshotStore(mock(JdbcTemplate.class)) {
			@Override
			public Optional<Snapshot> find(UUID aggregateId, int schemaVersion) {
				return Optional.empty();
			}

			@Override
			public void save(UUID aggregateId, String aggregateType, long version, UUID lastEventId, int schemaVersion,
					String stateHash, String stateJson) {
			}
		};
		walletRepository = new WalletRepository(eventStore, snapshotStore, serializer);
		walletHandler = new WalletCommandHandler(walletRepository, eventStore);
		transferRepository = new TransferRepository(eventStore);
		txManager = new RecordingTransactionManager();
		txTemplate = new TransactionTemplate(txManager);
		orchestrator = new TransferSagaOrchestrator(transferRepository, sagaStateStore, walletRepository,
				walletHandler, eventStore, txTemplate, 2, 0L, 0L);
	}

	private UUID openFundedWallet(long initialVnd) {
		UUID walletId = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		if (initialVnd > 0) {
			walletHandler.credit(walletId, UUID.randomUUID(), Money.vnd(initialVnd),
					"BANK_SETTLEMENT", AccountType.SETTLEMENT);
		}
		return walletId;
	}

	@Test
	void scenario1_happyPath_initiatesDebitsAndCompletesWithClearingCounterpartAndSeparateTransactions() {
		UUID sourceId = openFundedWallet(1_000_000L);
		UUID destId = openFundedWallet(200_000L);
		UUID transferId = UUID.randomUUID();

		Transfer result = orchestrator.transfer(transferId, sourceId, destId, Money.vnd(350_000L));

		assertThat(result.id()).isEqualTo(transferId);
		assertThat(result.status()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(result.type()).isEqualTo(TransferType.STANDARD);
		assertThat(result.originalTransferId()).isNull();
		assertThat(orchestrator.findTransfer(transferId)).isPresent().get()
				.extracting(Transfer::status).isEqualTo(TransferStatus.COMPLETED);
		assertThat(sagaStateStore.findById(transferId)).isPresent().get()
				.extracting(TransferSagaStateStore.TransferSagaSummary::status)
				.isEqualTo(TransferStatus.COMPLETED);

		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(650_000L));
		assertThat(walletRepository.load(destId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(550_000L));

		StoredEvent debitStored = eventStore.findEvent(Wallet.debitEventId(transferId, sourceId)).orElseThrow();
		assertThat(debitStored.event()).isEqualTo(new WalletDebited(sourceId, Money.vnd(350_000L), transferId,
				TransferSagaOrchestrator.TRANSFER_CLEARING_ACCOUNT, AccountType.CLEARING));

		StoredEvent creditStored = eventStore.findEvent(Wallet.creditEventId(transferId, destId)).orElseThrow();
		assertThat(creditStored.event()).isEqualTo(new WalletCredited(destId, Money.vnd(350_000L), transferId,
				TransferSagaOrchestrator.TRANSFER_CLEARING_ACCOUNT, AccountType.CLEARING));

		// ADR-006: wallet commands ran OUTSIDE Transfer's TransactionTemplate blocks
		assertThat(eventStore.walletAppendInsideTransferTxCount.get()).isZero();
		// 3 Transfer state transitions inside tx: INITIATED, DEBITED, COMPLETED
		assertThat(txManager.commitCount.get()).isEqualTo(3);
	}

	@Test
	void scenario2_preCheckRejections_rejectBeforeTransferInitiatedIsPersisted() {
		UUID sourceId = openFundedWallet(300_000_000L);
		UUID destId = openFundedWallet(0L);

		// > 50,000,000 VND single-transfer cap
		UUID overSingle = UUID.randomUUID();
		assertThatThrownBy(() -> orchestrator.transfer(overSingle, sourceId, destId, Money.vnd(50_000_001L)))
				.isInstanceOf(TransferPolicyViolationException.class);
		assertThat(transferRepository.load(overSingle)).isEmpty();
		assertThat(sagaStateStore.findById(overSingle)).isEmpty();

		// Same source & dest
		UUID sameId = UUID.randomUUID();
		assertThatThrownBy(() -> orchestrator.transfer(sameId, sourceId, sourceId, Money.vnd(100_000L)))
				.isInstanceOf(TransferPolicyViolationException.class);
		assertThat(transferRepository.load(sameId)).isEmpty();

		// Invalid amount / currency
		assertThatThrownBy(() -> orchestrator.transfer(UUID.randomUUID(), sourceId, destId, Money.vnd(0L)))
				.isInstanceOf(InvalidAmountException.class);
		assertThatThrownBy(() -> orchestrator.transfer(UUID.randomUUID(), sourceId, destId, new Money(100L, Currency.USD)))
				.isInstanceOf(IllegalArgumentException.class);

		// Unknown wallet
		UUID unknown = UUID.randomUUID();
		assertThatThrownBy(() -> orchestrator.transfer(UUID.randomUUID(), unknown, destId, Money.vnd(100_000L)))
				.isInstanceOf(WalletNotFoundException.class);
		assertThatThrownBy(() -> orchestrator.transfer(UUID.randomUUID(), sourceId, unknown, Money.vnd(100_000L)))
				.isInstanceOf(WalletNotFoundException.class);

		// Locked source or destination wallet
		UUID lockedSource = openFundedWallet(1_000_000L);
		walletHandler.lockWallet(lockedSource, "fraud");
		UUID lockedSourceTx = UUID.randomUUID();
		assertThatThrownBy(() -> orchestrator.transfer(lockedSourceTx, lockedSource, destId, Money.vnd(100_000L)))
				.isInstanceOf(WalletLockedException.class);
		assertThat(transferRepository.load(lockedSourceTx)).isEmpty();

		UUID lockedDest = openFundedWallet(0L);
		walletHandler.lockWallet(lockedDest, "compliance");
		UUID lockedDestTx = UUID.randomUUID();
		assertThatThrownBy(() -> orchestrator.transfer(lockedDestTx, sourceId, lockedDest, Money.vnd(100_000L)))
				.isInstanceOf(WalletLockedException.class);
		assertThat(transferRepository.load(lockedDestTx)).isEmpty();

		// Cumulative 24h > 200,000,000 VND
		for (int i = 0; i < 4; i++) {
			Transfer t = orchestrator.transfer(UUID.randomUUID(), sourceId, destId, Money.vnd(50_000_000L));
			assertThat(t.status()).isEqualTo(TransferStatus.COMPLETED);
		}
		UUID overDaily = UUID.randomUUID();
		assertThatThrownBy(() -> orchestrator.transfer(overDaily, sourceId, destId, Money.vnd(1L)))
				.isInstanceOf(TransferPolicyViolationException.class);
		assertThat(transferRepository.load(overDaily)).isEmpty();
		assertThat(sagaStateStore.findById(overDaily)).isEmpty();
	}

	@Test
	void scenario3_insufficientFundsOnSource_transitionsInitiatedToFailedWithUnchangedBalances() {
		UUID sourceId = openFundedWallet(100_000L);
		UUID destId = openFundedWallet(50_000L);
		UUID transferId = UUID.randomUUID();

		Transfer result = orchestrator.transfer(transferId, sourceId, destId, Money.vnd(250_000L));

		assertThat(result.status()).isEqualTo(TransferStatus.FAILED);
		assertThat(result.failureReason()).isNotBlank();
		assertThat(transferRepository.load(transferId)).get().extracting(Transfer::status)
				.isEqualTo(TransferStatus.FAILED);
		assertThat(sagaStateStore.findById(transferId)).get()
				.extracting(TransferSagaStateStore.TransferSagaSummary::status)
				.isEqualTo(TransferStatus.FAILED);
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(100_000L));
		assertThat(walletRepository.load(destId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(50_000L));
	}

	@Test
	void scenario4_destinationLockedAfterSourceDebit_compensatesSourceAndTransitionsToReversed() {
		UUID sourceId = openFundedWallet(500_000L);
		UUID destId = openFundedWallet(100_000L);
		UUID transferId = UUID.randomUUID();

		// Lock destination inside the debit hook right after source debit succeeds!
		eventStore.onAfterWalletDebit = () -> walletHandler.lockWallet(destId, "locked after debit");

		Transfer result = orchestrator.transfer(transferId, sourceId, destId, Money.vnd(200_000L));

		assertThat(result.status()).isEqualTo(TransferStatus.REVERSED);
		assertThat(result.failureReason()).contains("locked");
		assertThat(transferRepository.load(transferId)).get().extracting(Transfer::status)
				.isEqualTo(TransferStatus.REVERSED);
		assertThat(sagaStateStore.findById(transferId)).get()
				.extracting(TransferSagaStateStore.TransferSagaSummary::status)
				.isEqualTo(TransferStatus.REVERSED);
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(500_000L));
		assertThat(walletRepository.load(destId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(100_000L));
	}

	@Test
	void scenario5_sourceAlsoLockedBeforeCompensation_creditCompensationStillSucceedsAndReachesReversed() {
		UUID sourceId = openFundedWallet(500_000L);
		UUID destId = openFundedWallet(100_000L);
		UUID transferId = UUID.randomUUID();

		// Lock BOTH destination and source immediately after source debit succeeds
		eventStore.onAfterWalletDebit = () -> {
			walletHandler.lockWallet(destId, "dest locked after debit");
			walletHandler.lockWallet(sourceId, "source locked before compensation");
		};

		Transfer result = orchestrator.transfer(transferId, sourceId, destId, Money.vnd(200_000L));

		assertThat(result.status()).isEqualTo(TransferStatus.REVERSED);
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(500_000L));
		assertThat(walletRepository.load(destId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(100_000L));
		assertThat(sagaStateStore.findById(transferId)).get()
				.extracting(TransferSagaStateStore.TransferSagaSummary::status)
				.isEqualTo(TransferStatus.REVERSED);
	}

	@Test
	void scenario6_refund_reversesCompletedStandardTransferIsExemptFromDailyLimitAndRejectsInvalidStates() {
		UUID senderId = openFundedWallet(100_000_000L);
		UUID receiverId = openFundedWallet(200_000_000L);
		UUID thirdId = openFundedWallet(0L);

		// Exhaust receiver's 200M daily STANDARD limit
		for (int i = 0; i < 4; i++) {
			orchestrator.transfer(UUID.randomUUID(), receiverId, thirdId, Money.vnd(50_000_000L));
		}

		UUID originalId = UUID.randomUUID();
		Transfer original = orchestrator.transfer(originalId, senderId, receiverId, Money.vnd(400_000L));
		assertThat(original.status()).isEqualTo(TransferStatus.COMPLETED);

		// Receiver spends 300,000 VND -> first refund fails with insufficient funds
		walletHandler.debit(receiverId, UUID.randomUUID(), Money.vnd(300_000L),
				"MERCHANT_SETTLEMENT", AccountType.SETTLEMENT);
		UUID failedRefundId = UUID.randomUUID();
		Transfer failedRefund = orchestrator.refund(failedRefundId, originalId);
		assertThat(failedRefund.status()).isEqualTo(TransferStatus.FAILED);
		assertThat(failedRefund.type()).isEqualTo(TransferType.REFUND);
		assertThat(failedRefund.originalTransferId()).isEqualTo(originalId);

		// Receiver tops up -> later refund succeeds despite receiver being at 200M daily limit
		walletHandler.credit(receiverId, UUID.randomUUID(), Money.vnd(300_000L),
				"BANK_SETTLEMENT", AccountType.SETTLEMENT);
		UUID refundId = UUID.randomUUID();
		Transfer refund = orchestrator.refund(refundId, originalId);
		assertThat(refund.status()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(refund.type()).isEqualTo(TransferType.REFUND);
		assertThat(refund.originalTransferId()).isEqualTo(originalId);
		assertThat(refund.sourceWalletId()).isEqualTo(receiverId);
		assertThat(refund.destinationWalletId()).isEqualTo(senderId);
		assertThat(walletRepository.load(receiverId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(0L));
		assertThat(walletRepository.load(senderId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(100_000_000L));

		// Second refund on same original throws InvalidTransferStateException
		assertThatThrownBy(() -> orchestrator.refund(UUID.randomUUID(), originalId))
				.isInstanceOf(InvalidTransferStateException.class);

		// Refund on another REFUND throws InvalidTransferStateException
		assertThatThrownBy(() -> orchestrator.refund(UUID.randomUUID(), refundId))
				.isInstanceOf(InvalidTransferStateException.class);

		// Refund on DEBITED, FAILED, or REVERSED throws InvalidTransferStateException
		UUID unfundedId = openFundedWallet(0L);
		UUID failedStandardId = UUID.randomUUID();
		Transfer failedStandard = orchestrator.transfer(failedStandardId, unfundedId, senderId, Money.vnd(50_000_000L));
		assertThat(failedStandard.status()).isEqualTo(TransferStatus.FAILED);
		assertThatThrownBy(() -> orchestrator.refund(UUID.randomUUID(), failedStandardId))
				.isInstanceOf(InvalidTransferStateException.class);

		UUID debitedId = UUID.randomUUID();
		eventStore.failWalletAppendsForAggregate = thirdId;
		Transfer debited = orchestrator.transfer(debitedId, senderId, thirdId, Money.vnd(100_000L));
		eventStore.failWalletAppendsForAggregate = null;
		assertThat(debited.status()).isEqualTo(TransferStatus.DEBITED);
		assertThatThrownBy(() -> orchestrator.refund(UUID.randomUUID(), debitedId))
				.isInstanceOf(InvalidTransferStateException.class);

		UUID reversedId = UUID.randomUUID();
		eventStore.onAfterWalletDebit = () -> walletHandler.lockWallet(thirdId, "locked");
		Transfer reversed = orchestrator.transfer(reversedId, senderId, thirdId, Money.vnd(100_000L));
		eventStore.onAfterWalletDebit = null;
		assertThat(reversed.status()).isEqualTo(TransferStatus.REVERSED);
		assertThatThrownBy(() -> orchestrator.refund(UUID.randomUUID(), reversedId))
				.isInstanceOf(InvalidTransferStateException.class);
	}

	@Test
	void scenario7_idempotentRetryReturnsSameStateAndConflictingParametersThrowConflictingTransactionException() {
		UUID sourceId = openFundedWallet(1_000_000L);
		UUID destId = openFundedWallet(0L);
		UUID transferId = UUID.randomUUID();

		Transfer first = orchestrator.transfer(transferId, sourceId, destId, Money.vnd(300_000L));
		Transfer second = orchestrator.transfer(transferId, sourceId, destId, Money.vnd(300_000L));

		assertThat(first.status()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(second.status()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(700_000L));
		assertThat(walletRepository.load(destId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(300_000L));

		assertThatThrownBy(() -> orchestrator.transfer(transferId, sourceId, destId, Money.vnd(999_000L)))
				.isInstanceOf(ConflictingTransactionException.class);
		assertThatThrownBy(() -> orchestrator.transfer(transferId, destId, sourceId, Money.vnd(300_000L)))
				.isInstanceOf(ConflictingTransactionException.class);

		UUID refundId = UUID.randomUUID();
		Transfer r1 = orchestrator.refund(refundId, transferId);
		Transfer r2 = orchestrator.refund(refundId, transferId);
		assertThat(r1.status()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(r2.status()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(1_000_000L));

		assertThatThrownBy(() -> orchestrator.refund(refundId, UUID.randomUUID()))
				.isInstanceOf(ConflictingTransactionException.class);
	}

	@Test
	void contentionExhaustion_leavesSagaNonTerminalInInitiatedOrDebitedForRecoveryAndCompensatesAfterMaxRecoveryRetries() {
		UUID sourceId = openFundedWallet(1_000_000L);
		UUID destId = openFundedWallet(0L);
		UUID transferId = UUID.randomUUID();

		// Force OptimisticConcurrencyException on every wallet append for sourceId
		eventStore.failWalletAppendsForAggregate = sourceId;

		Transfer stuckInitiated = orchestrator.transfer(transferId, sourceId, destId, Money.vnd(200_000L));

		assertThat(stuckInitiated.status()).isEqualTo(TransferStatus.INITIATED);
		assertThat(sagaStateStore.findById(transferId)).get()
				.extracting(TransferSagaStateStore.TransferSagaSummary::status)
				.isEqualTo(TransferStatus.INITIATED);

		// When recovery reaches maxRetries at debit without debit event committed -> marks FAILED
		Transfer failedAfterMaxRetries = orchestrator.resume(transferId, 3, 3);
		assertThat(failedAfterMaxRetries.status()).isEqualTo(TransferStatus.FAILED);
		assertThat(failedAfterMaxRetries.failureReason()).contains("Max recovery retries exceeded at debit");

		// Now test contention exhaustion at CREDIT (DEBITED state): stays DEBITED during initial call,
		// and starts compensation when recovery reaches maxRetries!
		eventStore.failWalletAppendsForAggregate = destId;
		UUID transfer2Id = UUID.randomUUID();
		Transfer stuckDebited = orchestrator.transfer(transfer2Id, sourceId, destId, Money.vnd(200_000L));
		assertThat(stuckDebited.status()).isEqualTo(TransferStatus.DEBITED);
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(800_000L));

		// Recovery retry 1 of 3 still failing -> stays DEBITED
		Transfer stillDebited = orchestrator.resume(transfer2Id, 1, 3);
		assertThat(stillDebited.status()).isEqualTo(TransferStatus.DEBITED);

		// Recovery retry 3 of 3 still failing -> transitions DEBITED -> REVERSING -> REVERSED and refunds source!
		Transfer compensatedAfterMaxRetries = orchestrator.resume(transfer2Id, 3, 3);
		assertThat(compensatedAfterMaxRetries.status()).isEqualTo(TransferStatus.REVERSED);
		assertThat(compensatedAfterMaxRetries.failureReason()).contains("Max recovery retries exceeded at credit");
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(1_000_000L));

		// Also verify transient contention during REVERSING leaves transfer in REVERSING until next recovery pass
		eventStore.failWalletAppendsForAggregate = null;
		UUID transfer3Id = UUID.randomUUID();
		eventStore.onAfterWalletDebit = () -> {
			walletHandler.lockWallet(destId, "locked after debit");
			eventStore.failWalletAppendsForAggregate = sourceId;
		};
		Transfer stuckReversing = orchestrator.transfer(transfer3Id, sourceId, destId, Money.vnd(100_000L));
		assertThat(stuckReversing.status()).isEqualTo(TransferStatus.REVERSING);
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(900_000L));

		// Once contention clears, resume completes compensation -> REVERSED
		eventStore.failWalletAppendsForAggregate = null;
		Transfer recoveredReversed = orchestrator.resume(transfer3Id, 1, 3);
		assertThat(recoveredReversed.status()).isEqualTo(TransferStatus.REVERSED);
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(1_000_000L));

		// Re-running resume on terminal transfer synchronizes sagaStateStore and returns terminal state
		sagaStateStore.updateStatus(transfer3Id, TransferStatus.REVERSING, "drifted");
		Transfer resynced = orchestrator.resume(transfer3Id, 2, 3);
		assertThat(resynced.status()).isEqualTo(TransferStatus.REVERSED);
		assertThat(sagaStateStore.findById(transfer3Id)).get()
				.extracting(TransferSagaStateStore.TransferSagaSummary::status)
				.isEqualTo(TransferStatus.REVERSED);
	}

	@Test
	void resume_crashAfterDebitCommittedWhileTransferStillInitiated_detectsWalletDebitEventAndAdvancesToCompleted() {
		UUID sourceId = openFundedWallet(500_000L);
		UUID destId = openFundedWallet(100_000L);
		UUID transferId = UUID.randomUUID();

		// Simulate crash right after WalletDebited committed, before TransferDebitCompleted was saved
		sagaStateStore.insertInitiated(transferId, sourceId, destId, Money.vnd(150_000L),
				TransferType.STANDARD, null, TransferSagaOrchestrator.MAX_DAILY_OUTGOING_VND);
		Transfer initiated = Transfer.initiate(transferId, sourceId, destId, Money.vnd(150_000L),
				TransferType.STANDARD, null);
		transferRepository.save(initiated);
		walletHandler.debit(sourceId, transferId, Money.vnd(150_000L),
				TransferSagaOrchestrator.TRANSFER_CLEARING_ACCOUNT, AccountType.CLEARING);

		// Even if currentRetryCount >= maxRetries, resume MUST detect WalletDebited in eventStore and complete!
		Transfer resumed = orchestrator.resume(transferId, 3, 3);

		assertThat(resumed.status()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(350_000L));
		assertThat(walletRepository.load(destId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(250_000L));
		assertThat(sagaStateStore.findById(transferId)).get()
				.extracting(TransferSagaStateStore.TransferSagaSummary::status)
				.isEqualTo(TransferStatus.COMPLETED);
	}

	@Test
	void concurrentTransferAndResumeOnSameTransferId_serializeCleanlyWithoutDoubleDebitOrPayingBothWallets()
			throws Exception {
		UUID sourceId = openFundedWallet(1_000_000L);
		UUID destId = openFundedWallet(100_000L);
		UUID transferId = UUID.randomUUID();

		CountDownLatch debitCommitted = new CountDownLatch(1);
		CountDownLatch resumeStarted = new CountDownLatch(1);
		AtomicBoolean resumeCompleted = new AtomicBoolean(false);
		AtomicReference<Transfer> transferResultRef = new AtomicReference<>();
		AtomicReference<Transfer> resumeResultRef = new AtomicReference<>();
		AtomicReference<Throwable> failureRef = new AtomicReference<>();

		Thread resumeThread = new Thread(() -> {
			try {
				if (!debitCommitted.await(5, TimeUnit.SECONDS)) {
					throw new AssertionError("Timed out waiting for debitCommitted");
				}
				resumeStarted.countDown();
				// Even at maxRetries (3/3), resume must serialize behind transfer() and observe COMPLETED
				resumeResultRef.set(orchestrator.resume(transferId, 3, 3));
				resumeCompleted.set(true);
			} catch (Throwable t) {
				failureRef.compareAndSet(null, t);
			}
		}, "test-resume-thread");

		eventStore.onAfterWalletDebit = () -> {
			debitCommitted.countDown();
			try {
				assertThat(resumeStarted.await(5, TimeUnit.SECONDS)).isTrue();
				// Wait until dedicated resumeThread parks on the per-transferId striped lock (not terminated)
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
				while (System.nanoTime() < deadline
						&& resumeThread.getState() != Thread.State.WAITING
						&& resumeThread.getState() != Thread.State.TERMINATED) {
					Thread.yield();
				}
				assertThat(resumeThread.getState()).isEqualTo(Thread.State.WAITING);
				assertThat(resumeCompleted.get()).isFalse();
				assertThat(eventStore.findEvent(Wallet.creditEventId(transferId, destId))).isEmpty();
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				throw new AssertionError(ie);
			}
		};

		Thread transferThread = new Thread(() -> {
			try {
				transferResultRef.set(orchestrator.transfer(transferId, sourceId, destId, Money.vnd(300_000L)));
			} catch (Throwable t) {
				failureRef.compareAndSet(null, t);
			}
		}, "test-transfer-thread");

		resumeThread.start();
		transferThread.start();
		transferThread.join(10_000);
		resumeThread.join(10_000);

		assertThat(failureRef.get()).isNull();
		assertThat(transferResultRef.get()).isNotNull();
		assertThat(resumeResultRef.get()).isNotNull();
		assertThat(transferResultRef.get().status()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(resumeResultRef.get().status()).isEqualTo(TransferStatus.COMPLETED);

		// Exact balance conservation: no double debit, no double credit, no compensation paying source back
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(700_000L));
		assertThat(walletRepository.load(destId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(400_000L));
		assertThat(eventStore.findEvent(Wallet.debitEventId(transferId, sourceId))).isPresent();
		assertThat(eventStore.findEvent(Wallet.creditEventId(transferId, destId))).isPresent();
		assertThat(eventStore.findEvent(Wallet.creditEventId(transferId, sourceId))).isEmpty();
		assertThat(eventStore.walletAppendCountForTx(transferId)).isEqualTo(2);
	}

	@Test
	void constructorAndSpringPropertyBinding_validatesSeparateOrchestratorContentionConfig() {
		assertThatThrownBy(() -> new TransferSagaOrchestrator(transferRepository, sagaStateStore, walletRepository,
				walletHandler, eventStore, txTemplate, -1, 5L, 25L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TransferSagaOrchestrator(transferRepository, sagaStateStore, walletRepository,
				walletHandler, eventStore, txTemplate, 12, -1L, 25L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new TransferSagaOrchestrator(transferRepository, sagaStateStore, walletRepository,
				walletHandler, eventStore, txTemplate, 12, 25L, 5L))
				.isInstanceOf(IllegalArgumentException.class);

		new ApplicationContextRunner()
				.withBean(TransferRepository.class, () -> transferRepository)
				.withBean(TransferSagaStateStore.class, () -> sagaStateStore)
				.withBean(WalletRepository.class, () -> walletRepository)
				.withBean(WalletCommandHandler.class, () -> walletHandler)
				.withBean(EventStore.class, () -> eventStore)
				.withBean(TransactionTemplate.class, () -> txTemplate)
				.withBean(TransferSagaOrchestrator.class)
				.withPropertyValues(
						"transfer.orchestrator.max-contention-retries=4",
						"transfer.orchestrator.contention-backoff-min-ms=1",
						"transfer.orchestrator.contention-backoff-max-ms=2")
				.run(context -> assertThat(context).hasSingleBean(TransferSagaOrchestrator.class));
	}

	private final class RecordingTransactionManager implements PlatformTransactionManager {
		private final ThreadLocal<Integer> activeDepth = ThreadLocal.withInitial(() -> 0);
		final AtomicInteger commitCount = new AtomicInteger();

		boolean isActiveOnCurrentThread() {
			return activeDepth.get() > 0;
		}

		@Override
		public TransactionStatus getTransaction(TransactionDefinition definition) {
			activeDepth.set(activeDepth.get() + 1);
			return new SimpleTransactionStatus();
		}

		@Override
		public void commit(TransactionStatus status) {
			activeDepth.set(activeDepth.get() - 1);
			commitCount.incrementAndGet();
		}

		@Override
		public void rollback(TransactionStatus status) {
			activeDepth.set(activeDepth.get() - 1);
		}
	}

	private final class InMemoryEventStore implements EventStore {
		private final List<StoredEvent> events = new ArrayList<>();
		volatile Runnable onAfterWalletDebit;
		volatile UUID failWalletAppendsForAggregate;
		final AtomicInteger walletAppendInsideTransferTxCount = new AtomicInteger();
		private final Map<UUID, AtomicInteger> walletAppendsByTx = new HashMap<>();

		@Override
		public void append(UUID aggregateId, String aggregateType, long expectedVersion, List<PendingEvent> pending) {
			if ("Wallet".equals(aggregateType) && txManager.isActiveOnCurrentThread()) {
				walletAppendInsideTransferTxCount.incrementAndGet();
			}
			if ("Wallet".equals(aggregateType) && aggregateId.equals(failWalletAppendsForAggregate)) {
				throw new OptimisticConcurrencyException("Simulated wallet contention for " + aggregateId);
			}
			Runnable hookToRun = null;
			synchronized (events) {
				for (PendingEvent p : pending) {
					if (p.event() instanceof WalletDebited wd) {
						walletAppendsByTx.computeIfAbsent(wd.transactionId(), k -> new AtomicInteger()).incrementAndGet();
					} else if (p.event() instanceof WalletCredited wc) {
						walletAppendsByTx.computeIfAbsent(wc.transactionId(), k -> new AtomicInteger()).incrementAndGet();
					}
					if (containsUnlocked(p.eventId())) {
						throw new DuplicateEventException(p.eventId(), null);
					}
				}
				long currentVersion = latestVersionUnlocked(aggregateId);
				if (currentVersion != expectedVersion) {
					throw new OptimisticConcurrencyException(
							"Expected version " + expectedVersion + " but was " + currentVersion);
				}
				long version = expectedVersion;
				boolean sawWalletDebit = false;
				for (PendingEvent p : pending) {
					version++;
					events.add(new StoredEvent(p.eventId(), aggregateId, aggregateType, version, p.event().eventType(), 1,
							FIXED_NOW, p.correlationId(), p.causationId(), p.event()));
					if (p.event() instanceof WalletDebited) {
						sawWalletDebit = true;
					}
				}
				if (sawWalletDebit && onAfterWalletDebit != null) {
					hookToRun = onAfterWalletDebit;
					onAfterWalletDebit = null;
				}
			}
			if (hookToRun != null) {
				hookToRun.run();
			}
		}

		int walletAppendCountForTx(UUID txId) {
			synchronized (events) {
				AtomicInteger count = walletAppendsByTx.get(txId);
				return count != null ? count.get() : 0;
			}
		}

		@Override
		public List<StoredEvent> load(UUID aggregateId, long afterVersion) {
			synchronized (events) {
				return events.stream()
						.filter(e -> e.aggregateId().equals(aggregateId) && e.version() > afterVersion)
						.toList();
			}
		}

		@Override
		public boolean contains(UUID eventId) {
			synchronized (events) {
				return containsUnlocked(eventId);
			}
		}

		private boolean containsUnlocked(UUID eventId) {
			return events.stream().anyMatch(e -> e.eventId().equals(eventId));
		}

		@Override
		public Optional<StoredEvent> findEvent(UUID eventId) {
			synchronized (events) {
				return events.stream().filter(e -> e.eventId().equals(eventId)).findFirst();
			}
		}

		@Override
		public long latestVersion(UUID aggregateId) {
			synchronized (events) {
				return latestVersionUnlocked(aggregateId);
			}
		}

		private long latestVersionUnlocked(UUID aggregateId) {
			return events.stream().filter(e -> e.aggregateId().equals(aggregateId))
					.mapToLong(StoredEvent::version).max().orElse(0L);
		}
	}

	private static final class InMemorySagaStateStore extends TransferSagaStateStore {
		private final Map<UUID, TransferSagaSummary> rows = new HashMap<>();
		private final Clock clock;

		InMemorySagaStateStore(Clock clock) {
			super(mock(JdbcTemplate.class), clock);
			this.clock = clock;
		}

		@Override
		public synchronized boolean insertInitiated(UUID transferId, UUID sourceWalletId, UUID destinationWalletId,
				Money amount, TransferType type, UUID originalTransferId, long dailyLimitVnd) {
			if (rows.containsKey(transferId)) {
				return false;
			}
			if (type == TransferType.STANDARD) {
				long spent24h = sumOutgoingLast24Hours(sourceWalletId);
				long projected = Math.addExact(spent24h, amount.amount());
				if (projected > dailyLimitVnd) {
					throw new TransferPolicyViolationException(
							"Daily outgoing transfer limit of " + dailyLimitVnd + " VND exceeded for wallet "
									+ sourceWalletId);
				}
			} else if (type == TransferType.REFUND && originalTransferId != null) {
				boolean activeRefundExists = rows.values().stream()
						.anyMatch(r -> originalTransferId.equals(r.originalTransferId())
								&& r.status() != TransferStatus.FAILED
								&& r.status() != TransferStatus.REVERSED);
				if (activeRefundExists) {
					throw new InvalidTransferStateException(
							"Transfer " + originalTransferId + " already has an active or completed refund");
				}
			}
			Instant now = clock.instant();
			rows.put(transferId, new TransferSagaSummary(transferId, sourceWalletId, destinationWalletId,
					amount.amount(), amount.currency(), type, originalTransferId, TransferStatus.INITIATED,
					0, null, now, now));
			return true;
		}

		@Override
		public synchronized void updateStatus(UUID transferId, TransferStatus status, String failureReason) {
			TransferSagaSummary existing = rows.get(transferId);
			if (existing != null) {
				rows.put(transferId, new TransferSagaSummary(existing.transferId(), existing.sourceWalletId(),
						existing.destinationWalletId(), existing.amount(), existing.currency(), existing.type(),
						existing.originalTransferId(), status, existing.retryCount(), failureReason,
						existing.createdAt(), clock.instant()));
			}
		}

		@Override
		public synchronized long sumOutgoingLast24Hours(UUID sourceWalletId) {
			Instant cutoff = clock.instant().minus(Duration.ofHours(24));
			return rows.values().stream()
					.filter(r -> r.sourceWalletId().equals(sourceWalletId)
							&& r.type() == TransferType.STANDARD
							&& (r.status() == TransferStatus.INITIATED
									|| r.status() == TransferStatus.DEBITED
									|| r.status() == TransferStatus.COMPLETED)
							&& !r.createdAt().isBefore(cutoff))
					.mapToLong(TransferSagaSummary::amount)
					.sum();
		}

		@Override
		public synchronized Optional<TransferSagaSummary> findById(UUID transferId) {
			return Optional.ofNullable(rows.get(transferId));
		}
	}
}
