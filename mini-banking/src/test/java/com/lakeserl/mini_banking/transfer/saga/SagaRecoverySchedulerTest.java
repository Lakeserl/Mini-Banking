package com.lakeserl.mini_banking.transfer.saga;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.transfer.domain.Transfer;
import com.lakeserl.mini_banking.transfer.domain.TransferStatus;
import com.lakeserl.mini_banking.transfer.domain.TransferType;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferRepository;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferSagaStateStore;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;

@PostgresDataTest
class SagaRecoverySchedulerTest {

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
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	Clock clock;

	private SagaRecoveryScheduler scheduler;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM transfer_saga_state");
		scheduler = new SagaRecoveryScheduler(sagaStateStore, orchestrator,
				Duration.ofSeconds(SagaRecoveryScheduler.DEFAULT_STUCK_THRESHOLD_SECONDS),
				SagaRecoveryScheduler.DEFAULT_MAX_RETRIES,
				SagaRecoveryScheduler.DEFAULT_BATCH_SIZE);
	}

	private UUID openFundedWallet(long initialVnd) {
		UUID walletId = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		if (initialVnd > 0) {
			walletHandler.credit(walletId, UUID.randomUUID(), Money.vnd(initialVnd),
					"BANK_SETTLEMENT", AccountType.SETTLEMENT);
		}
		return walletId;
	}

	private void ageSagaRow(UUID transferId, Duration age) {
		OffsetDateTime oldTimestamp = OffsetDateTime.ofInstant(clock.instant().minus(age), ZoneOffset.UTC);
		jdbc.update("UPDATE transfer_saga_state SET updated_at = ? WHERE transfer_id = ?",
				oldTimestamp, transferId);
	}

	@Test
	void recoverOnce_stuckInInitiatedOver30s_completesDebitAndCreditToCompleted() {
		UUID sourceId = openFundedWallet(500_000L);
		UUID destId = openFundedWallet(100_000L);
		UUID transferId = UUID.randomUUID();

		tx.executeWithoutResult(status -> {
			sagaStateStore.insertInitiated(transferId, sourceId, destId, Money.vnd(200_000L),
					TransferType.STANDARD, null, TransferSagaOrchestrator.MAX_DAILY_OUTGOING_VND);
			transferRepository.save(Transfer.initiate(transferId, sourceId, destId, Money.vnd(200_000L),
					TransferType.STANDARD, null));
		});
		ageSagaRow(transferId, Duration.ofSeconds(45));

		int processed = scheduler.recoverOnce();

		assertThat(processed).isEqualTo(1);
		assertThat(transferRepository.load(transferId)).get().extracting(Transfer::status)
				.isEqualTo(TransferStatus.COMPLETED);
		assertThat(sagaStateStore.findById(transferId)).get().extracting(TransferSagaStateStore.TransferSagaSummary::status)
				.isEqualTo(TransferStatus.COMPLETED);
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(300_000L));
		assertThat(walletRepository.load(destId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(300_000L));
	}

	@Test
	void recoverOnce_stuckInInitiatedWhereWalletDebitedAlreadyExists_advancesToDebitedAndCompletesCredit() {
		UUID sourceId = openFundedWallet(500_000L);
		UUID destId = openFundedWallet(100_000L);
		UUID transferId = UUID.randomUUID();

		tx.executeWithoutResult(status -> {
			sagaStateStore.insertInitiated(transferId, sourceId, destId, Money.vnd(150_000L),
					TransferType.STANDARD, null, TransferSagaOrchestrator.MAX_DAILY_OUTGOING_VND);
			transferRepository.save(Transfer.initiate(transferId, sourceId, destId, Money.vnd(150_000L),
					TransferType.STANDARD, null));
		});
		walletHandler.debit(sourceId, transferId, Money.vnd(150_000L),
				TransferSagaOrchestrator.TRANSFER_CLEARING_ACCOUNT, AccountType.CLEARING);
		sagaStateStore.incrementRetry(transferId);
		sagaStateStore.incrementRetry(transferId);
		ageSagaRow(transferId, Duration.ofSeconds(45));

		int processed = scheduler.recoverOnce();

		assertThat(processed).isEqualTo(1);
		assertThat(transferRepository.load(transferId)).get().extracting(Transfer::status)
				.isEqualTo(TransferStatus.COMPLETED);
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(350_000L));
		assertThat(walletRepository.load(destId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(250_000L));
	}

	@Test
	void recoverOnce_stuckInDebitedOver30s_creditsDestinationWithoutDoubleDebit() {
		UUID sourceId = openFundedWallet(500_000L);
		UUID destId = openFundedWallet(100_000L);
		UUID transferId = UUID.randomUUID();

		tx.executeWithoutResult(status -> {
			sagaStateStore.insertInitiated(transferId, sourceId, destId, Money.vnd(200_000L),
					TransferType.STANDARD, null, TransferSagaOrchestrator.MAX_DAILY_OUTGOING_VND);
			Transfer transfer = Transfer.initiate(transferId, sourceId, destId, Money.vnd(200_000L),
					TransferType.STANDARD, null);
			transfer.markDebitCompleted();
			transferRepository.save(transfer);
			sagaStateStore.updateStatus(transferId, TransferStatus.DEBITED, null);
		});
		walletHandler.debit(sourceId, transferId, Money.vnd(200_000L),
				TransferSagaOrchestrator.TRANSFER_CLEARING_ACCOUNT, AccountType.CLEARING);
		ageSagaRow(transferId, Duration.ofSeconds(45));

		int processed = scheduler.recoverOnce();

		assertThat(processed).isEqualTo(1);
		assertThat(transferRepository.load(transferId)).get().extracting(Transfer::status)
				.isEqualTo(TransferStatus.COMPLETED);
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(300_000L));
		assertThat(walletRepository.load(destId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(300_000L));
	}

	@Test
	void recoverOnce_stuckInDebitedWithLockedDestinationWallet_compensatesSourceToReversed() {
		UUID sourceId = openFundedWallet(500_000L);
		UUID destId = openFundedWallet(100_000L);
		UUID transferId = UUID.randomUUID();

		tx.executeWithoutResult(status -> {
			sagaStateStore.insertInitiated(transferId, sourceId, destId, Money.vnd(200_000L),
					TransferType.STANDARD, null, TransferSagaOrchestrator.MAX_DAILY_OUTGOING_VND);
			Transfer transfer = Transfer.initiate(transferId, sourceId, destId, Money.vnd(200_000L),
					TransferType.STANDARD, null);
			transfer.markDebitCompleted();
			transferRepository.save(transfer);
			sagaStateStore.updateStatus(transferId, TransferStatus.DEBITED, null);
		});
		walletHandler.debit(sourceId, transferId, Money.vnd(200_000L),
				TransferSagaOrchestrator.TRANSFER_CLEARING_ACCOUNT, AccountType.CLEARING);
		walletHandler.lockWallet(destId, "locked destination");
		ageSagaRow(transferId, Duration.ofSeconds(45));

		int processed = scheduler.recoverOnce();

		assertThat(processed).isEqualTo(1);
		assertThat(transferRepository.load(transferId)).get().extracting(Transfer::status)
				.isEqualTo(TransferStatus.REVERSED);
		assertThat(sagaStateStore.findById(transferId)).get().extracting(TransferSagaStateStore.TransferSagaSummary::status)
				.isEqualTo(TransferStatus.REVERSED);
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(500_000L));
		assertThat(walletRepository.load(destId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(100_000L));
	}

	@Test
	void recoverOnce_stuckInReversingOver30s_completesCompensationToReversed() {
		UUID sourceId = openFundedWallet(500_000L);
		UUID destId = openFundedWallet(100_000L);
		UUID transferId = UUID.randomUUID();

		tx.executeWithoutResult(status -> {
			sagaStateStore.insertInitiated(transferId, sourceId, destId, Money.vnd(200_000L),
					TransferType.STANDARD, null, TransferSagaOrchestrator.MAX_DAILY_OUTGOING_VND);
			Transfer transfer = Transfer.initiate(transferId, sourceId, destId, Money.vnd(200_000L),
					TransferType.STANDARD, null);
			transfer.markDebitCompleted();
			transfer.startCompensation("destination locked");
			transferRepository.save(transfer);
			sagaStateStore.updateStatus(transferId, TransferStatus.REVERSING, "destination locked");
		});
		walletHandler.debit(sourceId, transferId, Money.vnd(200_000L),
				TransferSagaOrchestrator.TRANSFER_CLEARING_ACCOUNT, AccountType.CLEARING);
		ageSagaRow(transferId, Duration.ofSeconds(45));

		int processed = scheduler.recoverOnce();

		assertThat(processed).isEqualTo(1);
		assertThat(transferRepository.load(transferId)).get().extracting(Transfer::status)
				.isEqualTo(TransferStatus.REVERSED);
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(500_000L));
	}

	@Test
	void recoverOnce_transferUpdatedLessThan30sAgo_isIgnored() {
		UUID sourceId = openFundedWallet(500_000L);
		UUID destId = openFundedWallet(100_000L);
		UUID transferId = UUID.randomUUID();

		tx.executeWithoutResult(status -> {
			sagaStateStore.insertInitiated(transferId, sourceId, destId, Money.vnd(200_000L),
					TransferType.STANDARD, null, TransferSagaOrchestrator.MAX_DAILY_OUTGOING_VND);
			transferRepository.save(Transfer.initiate(transferId, sourceId, destId, Money.vnd(200_000L),
					TransferType.STANDARD, null));
		});

		assertThat(scheduler.recoverOnce()).isZero();
		assertThat(transferRepository.load(transferId)).get().extracting(Transfer::status)
				.isEqualTo(TransferStatus.INITIATED);
	}

	@Test
	void recoverOnce_isolatedFailureOnOneStuckSaga_doesNotPreventSubsequentStuckSagasInBatchFromRecovering() {
		UUID sourceId = openFundedWallet(500_000L);
		UUID destId = openFundedWallet(100_000L);

		UUID brokenId = UUID.randomUUID();
		tx.executeWithoutResult(status -> sagaStateStore.insertInitiated(brokenId, sourceId, destId,
				Money.vnd(50_000L), TransferType.STANDARD, null, TransferSagaOrchestrator.MAX_DAILY_OUTGOING_VND));
		ageSagaRow(brokenId, Duration.ofSeconds(60));

		UUID validId = UUID.randomUUID();
		tx.executeWithoutResult(status -> {
			sagaStateStore.insertInitiated(validId, sourceId, destId, Money.vnd(200_000L),
					TransferType.STANDARD, null, TransferSagaOrchestrator.MAX_DAILY_OUTGOING_VND);
			transferRepository.save(Transfer.initiate(validId, sourceId, destId, Money.vnd(200_000L),
					TransferType.STANDARD, null));
		});
		ageSagaRow(validId, Duration.ofSeconds(45));

		int processed = scheduler.recoverOnce();

		assertThat(processed).isEqualTo(1);
		assertThat(transferRepository.load(validId)).get().extracting(Transfer::status)
				.isEqualTo(TransferStatus.COMPLETED);
	}
}
