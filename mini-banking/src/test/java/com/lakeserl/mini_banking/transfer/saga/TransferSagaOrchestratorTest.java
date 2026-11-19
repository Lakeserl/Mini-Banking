package com.lakeserl.mini_banking.transfer.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;
import com.lakeserl.mini_banking.transfer.domain.Transfer;
import com.lakeserl.mini_banking.transfer.domain.TransferStatus;
import com.lakeserl.mini_banking.transfer.domain.TransferType;
import com.lakeserl.mini_banking.transfer.domain.exceptions.InvalidTransferStateException;
import com.lakeserl.mini_banking.transfer.domain.exceptions.TransferNotFoundException;
import com.lakeserl.mini_banking.transfer.domain.exceptions.TransferPolicyViolationException;
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
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;

@PostgresDataTest
class TransferSagaOrchestratorTest {

	@Autowired
	TransferSagaOrchestrator orchestrator;

	@Autowired
	TransferRepository transferRepository;

	@Autowired
	TransferSagaStateStore sagaStateStore;

	@Autowired
	WalletCommandHandler walletHandler;

	@Autowired
	WalletRepository walletRepository;

	@Autowired
	EventStore eventStore;

	private UUID openFundedWallet(long initialVnd) {
		UUID walletId = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		if (initialVnd > 0) {
			walletHandler.credit(walletId, UUID.randomUUID(), Money.vnd(initialVnd),
					"BANK_SETTLEMENT", AccountType.SETTLEMENT);
		}
		return walletId;
	}

	@Test
	void transfer_happyPath_debitsSourceAndCreditsDestinationWithTransferClearingCounterpart() {
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
	}

	@Test
	void transfer_preCheckRejections_throwBeforeInitiatingTransfer() {
		UUID sourceId = openFundedWallet(300_000_000L);
		UUID destId = openFundedWallet(0L);

		// 1. Single-transfer limit > 50,000,000 VND
		UUID overSingleId = UUID.randomUUID();
		assertThatThrownBy(() -> orchestrator.transfer(overSingleId, sourceId, destId, Money.vnd(50_000_001L)))
				.isInstanceOf(TransferPolicyViolationException.class);
		assertThat(transferRepository.load(overSingleId)).isEmpty();
		assertThat(sagaStateStore.findById(overSingleId)).isEmpty();

		// 2. Same source and destination wallet
		UUID sameWalletTx = UUID.randomUUID();
		assertThatThrownBy(() -> orchestrator.transfer(sameWalletTx, sourceId, sourceId, Money.vnd(100_000L)))
				.isInstanceOf(TransferPolicyViolationException.class);
		assertThat(transferRepository.load(sameWalletTx)).isEmpty();

		// 3. Invalid amount (zero / negative) and currency
		assertThatThrownBy(() -> orchestrator.transfer(UUID.randomUUID(), sourceId, destId, Money.vnd(0L)))
				.isInstanceOf(InvalidAmountException.class);
		assertThatThrownBy(() -> orchestrator.transfer(UUID.randomUUID(), sourceId, destId, new Money(100L, Currency.USD)))
				.isInstanceOf(IllegalArgumentException.class);

		// 4. Unknown source or destination wallet
		UUID unknownWallet = UUID.randomUUID();
		assertThatThrownBy(() -> orchestrator.transfer(UUID.randomUUID(), unknownWallet, destId, Money.vnd(100_000L)))
				.isInstanceOf(WalletNotFoundException.class);
		assertThatThrownBy(() -> orchestrator.transfer(UUID.randomUUID(), sourceId, unknownWallet, Money.vnd(100_000L)))
				.isInstanceOf(WalletNotFoundException.class);

		// 5. Locked source or destination wallet
		UUID lockedSource = openFundedWallet(1_000_000L);
		walletHandler.lockWallet(lockedSource, "fraud hold");
		UUID lockedSourceTx = UUID.randomUUID();
		assertThatThrownBy(() -> orchestrator.transfer(lockedSourceTx, lockedSource, destId, Money.vnd(100_000L)))
				.isInstanceOf(WalletLockedException.class);
		assertThat(transferRepository.load(lockedSourceTx)).isEmpty();

		UUID lockedDest = openFundedWallet(0L);
		walletHandler.lockWallet(lockedDest, "compliance hold");
		UUID lockedDestTx = UUID.randomUUID();
		assertThatThrownBy(() -> orchestrator.transfer(lockedDestTx, sourceId, lockedDest, Money.vnd(100_000L)))
				.isInstanceOf(WalletLockedException.class);
		assertThat(transferRepository.load(lockedDestTx)).isEmpty();

		// 6. Cumulative 24h outgoing > 200,000,000 VND
		for (int i = 0; i < 4; i++) {
			Transfer t = orchestrator.transfer(UUID.randomUUID(), sourceId, destId, Money.vnd(50_000_000L));
			assertThat(t.status()).isEqualTo(TransferStatus.COMPLETED);
		}
		UUID overDailyId = UUID.randomUUID();
		assertThatThrownBy(() -> orchestrator.transfer(overDailyId, sourceId, destId, Money.vnd(1L)))
				.isInstanceOf(TransferPolicyViolationException.class);
		assertThat(transferRepository.load(overDailyId)).isEmpty();
		assertThat(sagaStateStore.findById(overDailyId)).isEmpty();
	}

	@Test
	void transfer_whenSourceHasInsufficientFunds_transitionsInitiatedToFailedAndLeavesBalancesUnchanged() {
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
	void transfer_whenDestinationLockedAfterSourceDebit_compensatesSourceAndTransitionsToReversed() {
		UUID sourceId = openFundedWallet(500_000L);
		UUID destId = openFundedWallet(100_000L);
		UUID transferId = UUID.randomUUID();

		// Simulate in-flight transfer that completed source debit and persisted DEBITED, then dest was locked
		sagaStateStore.insertInitiated(transferId, sourceId, destId, Money.vnd(200_000L),
				TransferType.STANDARD, null, TransferSagaOrchestrator.MAX_DAILY_OUTGOING_VND);
		Transfer transfer = Transfer.initiate(transferId, sourceId, destId, Money.vnd(200_000L),
				TransferType.STANDARD, null);
		walletHandler.debit(sourceId, transferId, Money.vnd(200_000L),
				TransferSagaOrchestrator.TRANSFER_CLEARING_ACCOUNT, AccountType.CLEARING);
		transfer.markDebitCompleted();
		transferRepository.save(transfer);
		sagaStateStore.updateStatus(transferId, TransferStatus.DEBITED, null);

		walletHandler.lockWallet(destId, "destination frozen mid-saga");

		Transfer resumed = orchestrator.resume(transferId, 0, 3);

		assertThat(resumed.status()).isEqualTo(TransferStatus.REVERSED);
		assertThat(resumed.failureReason()).contains("locked");
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
	void transfer_whenSourceAlsoLockedBeforeCompensation_creditCompensationStillSucceedsAndReachesReversed() {
		UUID sourceId = openFundedWallet(500_000L);
		UUID destId = openFundedWallet(100_000L);
		UUID transferId = UUID.randomUUID();

		sagaStateStore.insertInitiated(transferId, sourceId, destId, Money.vnd(200_000L),
				TransferType.STANDARD, null, TransferSagaOrchestrator.MAX_DAILY_OUTGOING_VND);
		Transfer transfer = Transfer.initiate(transferId, sourceId, destId, Money.vnd(200_000L),
				TransferType.STANDARD, null);
		walletHandler.debit(sourceId, transferId, Money.vnd(200_000L),
				TransferSagaOrchestrator.TRANSFER_CLEARING_ACCOUNT, AccountType.CLEARING);
		transfer.markDebitCompleted();
		transferRepository.save(transfer);
		sagaStateStore.updateStatus(transferId, TransferStatus.DEBITED, null);

		walletHandler.lockWallet(destId, "destination locked");
		walletHandler.lockWallet(sourceId, "source also locked before compensation");

		Transfer resumed = orchestrator.resume(transferId, 0, 3);

		assertThat(resumed.status()).isEqualTo(TransferStatus.REVERSED);
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(500_000L));
		assertThat(sagaStateStore.findById(transferId)).get()
				.extracting(TransferSagaStateStore.TransferSagaSummary::status)
				.isEqualTo(TransferStatus.REVERSED);
	}

	@Test
	void refund_enforcesOriginalTransferRulesExemptFromDailyLimitAndAllowsRetryAfterFailedRefund() {
		UUID senderId = openFundedWallet(100_000_000L);
		UUID receiverId = openFundedWallet(200_000_000L);
		UUID thirdId = openFundedWallet(0L);

		// Exhaust receiver's 200M daily STANDARD outgoing limit first
		for (int i = 0; i < 4; i++) {
			orchestrator.transfer(UUID.randomUUID(), receiverId, thirdId, Money.vnd(50_000_000L));
		}

		// Standard transfer from sender -> receiver of 400,000 VND
		UUID originalId = UUID.randomUUID();
		Transfer original = orchestrator.transfer(originalId, senderId, receiverId, Money.vnd(400_000L));
		assertThat(original.status()).isEqualTo(TransferStatus.COMPLETED);

		// Receiver spends 300,000 VND so first refund attempt will fail with insufficient funds
		// Wait: receiver cannot do a standard transfer because receiver is at 200M daily limit,
		// so debit receiver directly via WalletCommandHandler to simulate receiver spending funds!
		walletHandler.debit(receiverId, UUID.randomUUID(), Money.vnd(300_000L),
				"MERCHANT_SETTLEMENT", AccountType.SETTLEMENT);
		assertThat(walletRepository.load(receiverId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(100_000L));

		UUID failedRefundId = UUID.randomUUID();
		Transfer failedRefund = orchestrator.refund(failedRefundId, originalId);
		assertThat(failedRefund.status()).isEqualTo(TransferStatus.FAILED);
		assertThat(failedRefund.type()).isEqualTo(TransferType.REFUND);
		assertThat(failedRefund.originalTransferId()).isEqualTo(originalId);

		// Receiver tops up 300,000 VND; later refund succeeds even though receiver's 24h STANDARD limit is exhausted
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

		// Second sequential refund on already-refunded original throws InvalidTransferStateException
		assertThatThrownBy(() -> orchestrator.refund(UUID.randomUUID(), originalId))
				.isInstanceOf(InvalidTransferStateException.class);

		// Refund on another REFUND throws InvalidTransferStateException
		assertThatThrownBy(() -> orchestrator.refund(UUID.randomUUID(), refundId))
				.isInstanceOf(InvalidTransferStateException.class);

		// Refund on DEBITED, FAILED, or REVERSED transfer throws InvalidTransferStateException
		UUID unfundedId = openFundedWallet(0L);
		UUID failedStandardId = UUID.randomUUID();
		Transfer failedStandard = orchestrator.transfer(failedStandardId, unfundedId, senderId, Money.vnd(50_000_000L));
		assertThat(failedStandard.status()).isEqualTo(TransferStatus.FAILED);
		assertThatThrownBy(() -> orchestrator.refund(UUID.randomUUID(), failedStandardId))
				.isInstanceOf(InvalidTransferStateException.class);

		UUID debitedId = UUID.randomUUID();
		sagaStateStore.insertInitiated(debitedId, senderId, thirdId, Money.vnd(100_000L),
				TransferType.STANDARD, null, TransferSagaOrchestrator.MAX_DAILY_OUTGOING_VND);
		Transfer debited = Transfer.initiate(debitedId, senderId, thirdId, Money.vnd(100_000L),
				TransferType.STANDARD, null);
		debited.markDebitCompleted();
		transferRepository.save(debited);
		sagaStateStore.updateStatus(debitedId, TransferStatus.DEBITED, null);
		assertThatThrownBy(() -> orchestrator.refund(UUID.randomUUID(), debitedId))
				.isInstanceOf(InvalidTransferStateException.class);

		UUID reversedId = UUID.randomUUID();
		sagaStateStore.insertInitiated(reversedId, senderId, thirdId, Money.vnd(100_000L),
				TransferType.STANDARD, null, TransferSagaOrchestrator.MAX_DAILY_OUTGOING_VND);
		Transfer reversed = Transfer.initiate(reversedId, senderId, thirdId, Money.vnd(100_000L),
				TransferType.STANDARD, null);
		reversed.markDebitCompleted();
		reversed.startCompensation("dest locked");
		reversed.markCompensated();
		transferRepository.save(reversed);
		sagaStateStore.updateStatus(reversedId, TransferStatus.REVERSED, "dest locked");
		assertThatThrownBy(() -> orchestrator.refund(UUID.randomUUID(), reversedId))
				.isInstanceOf(InvalidTransferStateException.class);

		// Refund on non-existent original throws TransferNotFoundException
		assertThatThrownBy(() -> orchestrator.refund(UUID.randomUUID(), UUID.randomUUID()))
				.isInstanceOf(TransferNotFoundException.class);
	}

	@Test
	void transferAndRefund_idempotentRetryReturnsSameStateAndConflictingParamsThrow() {
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
		assertThatThrownBy(() -> orchestrator.refund(transferId, transferId))
				.isInstanceOf(IllegalArgumentException.class);

		UUID refundId = UUID.randomUUID();
		Transfer r1 = orchestrator.refund(refundId, transferId);
		Transfer r2 = orchestrator.refund(refundId, transferId);
		assertThat(r1.status()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(r2.status()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(1_000_000L));
		assertThat(walletRepository.load(destId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(0L));

		assertThatThrownBy(() -> orchestrator.refund(refundId, UUID.randomUUID()))
				.isInstanceOf(ConflictingTransactionException.class);
		assertThatThrownBy(() -> orchestrator.transfer(refundId, destId, sourceId, Money.vnd(300_000L)))
				.isInstanceOf(ConflictingTransactionException.class);
	}
}
