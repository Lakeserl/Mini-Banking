package com.lakeserl.mini_banking.deposit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.lakeserl.mini_banking.deposit.domain.Deposit;
import com.lakeserl.mini_banking.deposit.domain.DepositStatus;
import com.lakeserl.mini_banking.deposit.domain.events.DepositBankVerified;
import com.lakeserl.mini_banking.deposit.domain.events.DepositConfirmed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositFailed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositRequested;
import com.lakeserl.mini_banking.deposit.infrastructure.DepositBankReferenceStore;
import com.lakeserl.mini_banking.deposit.infrastructure.DepositRepository;
import com.lakeserl.mini_banking.mockbank.MockBankGateway;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.DepositVerificationResult;
import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.shared.eventsourcing.OptimisticConcurrencyException;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler.ApplyResult;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.domain.events.WalletCredited;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletLockedException;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;

class DepositCommandHandlerUnitTest {

	private DepositRepository depositRepository;
	private DepositBankReferenceStore bankReferenceStore;
	private WalletRepository walletRepository;
	private WalletCommandHandler walletCommandHandler;
	private MockBankGateway mockBankGateway;
	private EventStore eventStore;
	private TransactionTemplate tx;
	private DepositCommandHandler handler;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		depositRepository = mock(DepositRepository.class);
		bankReferenceStore = mock(DepositBankReferenceStore.class);
		walletRepository = mock(WalletRepository.class);
		walletCommandHandler = mock(WalletCommandHandler.class);
		mockBankGateway = mock(MockBankGateway.class);
		eventStore = mock(EventStore.class);
		tx = mock(TransactionTemplate.class);

		when(tx.execute(any(TransactionCallback.class))).thenAnswer(inv -> {
			TransactionCallback<?> cb = inv.getArgument(0);
			return cb.doInTransaction(mock(TransactionStatus.class));
		});
		when(eventStore.findEvent(any())).thenReturn(Optional.empty());

		handler = new DepositCommandHandler(depositRepository, bankReferenceStore, walletRepository,
				walletCommandHandler, mockBankGateway, eventStore, tx, 3);
	}

	@Test
	void newDepositOnLockedWalletFailsFastBeforeClaimOrBankCall() {
		UUID walletId = UUID.randomUUID();
		UUID depositId = UUID.randomUUID();
		Wallet locked = Wallet.open(walletId, UUID.randomUUID(), Currency.VND);
		locked.lock("compliance");

		when(depositRepository.load(depositId)).thenReturn(Optional.empty());
		when(walletRepository.load(walletId)).thenReturn(Optional.of(locked));

		assertThatThrownBy(() -> handler.deposit(depositId, walletId, Money.vnd(100_000), "REF-LOCK-NEW"))
				.isInstanceOf(WalletLockedException.class);

		verifyNoInteractions(bankReferenceStore, mockBankGateway, walletCommandHandler);
		verify(depositRepository, never()).save(any());
	}

	@Test
	void bankSuccessPersistsDepositBankVerifiedBeforeCreditSettlementThenConfirms() {
		UUID walletId = UUID.randomUUID();
		UUID depositId = UUID.randomUUID();
		Money amount = Money.vnd(250_000);
		Wallet active = Wallet.open(walletId, UUID.randomUUID(), Currency.VND);

		Deposit requested = Deposit.fromHistory(List.of(new DepositRequested(depositId, walletId, amount, "REF-SEQ-1")));
		Deposit verified = Deposit.fromHistory(List.of(
				new DepositRequested(depositId, walletId, amount, "REF-SEQ-1"),
				new DepositBankVerified(depositId)));

		when(depositRepository.load(depositId))
				.thenReturn(Optional.empty())
				.thenReturn(Optional.of(requested))
				.thenReturn(Optional.of(verified));
		when(walletRepository.load(walletId)).thenReturn(Optional.of(active));
		when(bankReferenceStore.tryClaim(depositId, "REF-SEQ-1", walletId, amount)).thenReturn(true);
		when(mockBankGateway.verifyDeposit(depositId, amount, "REF-SEQ-1"))
				.thenReturn(DepositVerificationResult.success("REF-SEQ-1"));
		when(walletCommandHandler.creditSettlement(walletId, depositId, amount,
				DepositCommandHandler.BANK_SETTLEMENT_ACCOUNT)).thenReturn(ApplyResult.APPLIED);

		DepositStatus status = handler.deposit(depositId, walletId, amount, "REF-SEQ-1");

		assertThat(status).isEqualTo(DepositStatus.CONFIRMED);
		InOrder order = inOrder(bankReferenceStore, depositRepository, mockBankGateway, walletCommandHandler);
		order.verify(bankReferenceStore).tryClaim(depositId, "REF-SEQ-1", walletId, amount);
		order.verify(depositRepository).save(any(Deposit.class));
		order.verify(mockBankGateway).verifyDeposit(depositId, amount, "REF-SEQ-1");
		order.verify(depositRepository).save(requested);
		order.verify(walletCommandHandler).creditSettlement(walletId, depositId, amount,
				DepositCommandHandler.BANK_SETTLEMENT_ACCOUNT);
		order.verify(depositRepository).save(verified);
		assertThat(requested.isBankVerified()).isTrue();
		assertThat(verified.status()).isEqualTo(DepositStatus.CONFIRMED);
	}

	@Test
	void existingConfirmedReturnsConfirmedWithoutCreditingWalletOrCallingBank() {
		UUID walletId = UUID.randomUUID();
		UUID depositId = UUID.randomUUID();
		Money amount = Money.vnd(300_000);
		Deposit confirmed = Deposit.fromHistory(List.of(
				new DepositRequested(depositId, walletId, amount, "REF-CONF-1"),
				new DepositBankVerified(depositId),
				new DepositConfirmed(depositId)));
		when(depositRepository.load(depositId)).thenReturn(Optional.of(confirmed));

		assertThat(handler.deposit(depositId, walletId, amount, "REF-CONF-1")).isEqualTo(DepositStatus.CONFIRMED);

		verifyNoInteractions(walletCommandHandler, mockBankGateway, bankReferenceStore);
	}

	@Test
	void existingPendingAlreadyBankVerifiedSettlesWithoutCallingBank() {
		UUID walletId = UUID.randomUUID();
		UUID depositId = UUID.randomUUID();
		Money amount = Money.vnd(400_000);
		Deposit verified = Deposit.fromHistory(List.of(
				new DepositRequested(depositId, walletId, amount, "REF-VER-1"),
				new DepositBankVerified(depositId)));
		when(depositRepository.load(depositId)).thenReturn(Optional.of(verified));
		when(walletCommandHandler.creditSettlement(walletId, depositId, amount,
				DepositCommandHandler.BANK_SETTLEMENT_ACCOUNT)).thenReturn(ApplyResult.APPLIED);

		assertThat(handler.deposit(depositId, walletId, amount, "REF-VER-1")).isEqualTo(DepositStatus.CONFIRMED);

		verifyNoInteractions(mockBankGateway);
		verify(walletCommandHandler).creditSettlement(walletId, depositId, amount,
				DepositCommandHandler.BANK_SETTLEMENT_ACCOUNT);
		assertThat(verified.status()).isEqualTo(DepositStatus.CONFIRMED);
	}

	@Test
	void preV9LegacyPendingWithExistingWalletCreditedSettlesWithoutCallingBank() {
		UUID walletId = UUID.randomUUID();
		UUID depositId = UUID.randomUUID();
		Money amount = Money.vnd(500_000);
		Deposit legacyPending = Deposit.fromHistory(List.of(
				new DepositRequested(depositId, walletId, amount, "REF-LEGACY-1")));
		Deposit afterMarkVerified = Deposit.fromHistory(List.of(
				new DepositRequested(depositId, walletId, amount, "REF-LEGACY-1"),
				new DepositBankVerified(depositId)));

		when(depositRepository.load(depositId))
				.thenReturn(Optional.of(legacyPending))
				.thenReturn(Optional.of(legacyPending))
				.thenReturn(Optional.of(afterMarkVerified));
		when(eventStore.findEvent(Wallet.creditEventId(depositId, walletId)))
				.thenReturn(Optional.of(storedCredit(depositId, walletId, amount)));
		when(walletCommandHandler.creditSettlement(walletId, depositId, amount,
				DepositCommandHandler.BANK_SETTLEMENT_ACCOUNT)).thenReturn(ApplyResult.ALREADY_APPLIED);

		assertThat(handler.deposit(depositId, walletId, amount, "REF-LEGACY-1")).isEqualTo(DepositStatus.CONFIRMED);

		verifyNoInteractions(mockBankGateway);
		verify(walletCommandHandler).creditSettlement(walletId, depositId, amount,
				DepositCommandHandler.BANK_SETTLEMENT_ACCOUNT);
		assertThat(legacyPending.isBankVerified()).isTrue();
		assertThat(afterMarkVerified.status()).isEqualTo(DepositStatus.CONFIRMED);
	}

	@Test
	void bankRejectedSettlesInsteadOfFailingWhenWalletAlreadyCredited() {
		UUID walletId = UUID.randomUUID();
		UUID depositId = UUID.randomUUID();
		Money amount = Money.vnd(200_000);
		Deposit pending = Deposit.fromHistory(List.of(new DepositRequested(depositId, walletId, amount, "REF-REJ-CRED")));
		Deposit afterVerify = Deposit.fromHistory(List.of(
				new DepositRequested(depositId, walletId, amount, "REF-REJ-CRED"),
				new DepositBankVerified(depositId)));

		when(depositRepository.load(depositId))
				.thenReturn(Optional.of(pending))
				.thenReturn(Optional.of(pending))
				.thenReturn(Optional.of(afterVerify));
		when(eventStore.findEvent(Wallet.creditEventId(depositId, walletId)))
				.thenReturn(Optional.empty())
				.thenReturn(Optional.of(storedCredit(depositId, walletId, amount)));
		when(mockBankGateway.verifyDeposit(depositId, amount, "REF-REJ-CRED"))
				.thenReturn(DepositVerificationResult.rejected("stale"));
		when(walletCommandHandler.creditSettlement(walletId, depositId, amount,
				DepositCommandHandler.BANK_SETTLEMENT_ACCOUNT)).thenReturn(ApplyResult.ALREADY_APPLIED);

		assertThat(handler.deposit(depositId, walletId, amount, "REF-REJ-CRED")).isEqualTo(DepositStatus.CONFIRMED);
		assertThat(afterVerify.status()).isEqualTo(DepositStatus.CONFIRMED);
	}

	@Test
	void bankRejectedLosingVersionRaceToDepositBankVerifiedSettlesCleanly() {
		UUID walletId = UUID.randomUUID();
		UUID depositId = UUID.randomUUID();
		Money amount = Money.vnd(150_000);
		Deposit pendingFirst = Deposit.fromHistory(List.of(new DepositRequested(depositId, walletId, amount, "REF-RACE-REJ")));
		Deposit verifiedByPeer = Deposit.fromHistory(List.of(
				new DepositRequested(depositId, walletId, amount, "REF-RACE-REJ"),
				new DepositBankVerified(depositId)));

		when(depositRepository.load(depositId))
				.thenReturn(Optional.of(pendingFirst))
				.thenReturn(Optional.of(pendingFirst))
				.thenReturn(Optional.of(verifiedByPeer))
				.thenReturn(Optional.of(verifiedByPeer));
		when(mockBankGateway.verifyDeposit(depositId, amount, "REF-RACE-REJ"))
				.thenReturn(DepositVerificationResult.rejected("declined"));
		doThrow(new OptimisticConcurrencyException("version conflict on " + depositId))
				.doNothing()
				.when(depositRepository).save(any(Deposit.class));
		when(walletCommandHandler.creditSettlement(walletId, depositId, amount,
				DepositCommandHandler.BANK_SETTLEMENT_ACCOUNT)).thenReturn(ApplyResult.APPLIED);

		DepositStatus status = handler.deposit(depositId, walletId, amount, "REF-RACE-REJ");

		assertThat(status).isEqualTo(DepositStatus.CONFIRMED);
		verify(walletCommandHandler).creditSettlement(walletId, depositId, amount,
				DepositCommandHandler.BANK_SETTLEMENT_ACCOUNT);
		assertThat(verifiedByPeer.status()).isEqualTo(DepositStatus.CONFIRMED);
	}

	@Test
	void lateBankSuccessOnAlreadyFailedDepositThrowsIllegalStateExceptionWithoutCreditingWallet() {
		UUID walletId = UUID.randomUUID();
		UUID depositId = UUID.randomUUID();
		Money amount = Money.vnd(120_000);
		Deposit pending = Deposit.fromHistory(List.of(new DepositRequested(depositId, walletId, amount, "REF-LATE-OK")));
		Deposit failedByPeer = Deposit.fromHistory(List.of(
				new DepositRequested(depositId, walletId, amount, "REF-LATE-OK"),
				new DepositFailed(depositId, "declined")));

		when(depositRepository.load(depositId))
				.thenReturn(Optional.of(pending))
				.thenReturn(Optional.of(failedByPeer));
		when(mockBankGateway.verifyDeposit(depositId, amount, "REF-LATE-OK"))
				.thenReturn(DepositVerificationResult.success("REF-LATE-OK"));

		assertThatThrownBy(() -> handler.deposit(depositId, walletId, amount, "REF-LATE-OK"))
				.isInstanceOf(IllegalStateException.class);

		verifyNoInteractions(walletCommandHandler);
	}

	@Test
	void tryClaimReturningFalseReloadsAndContinuesExistingDeposit() {
		UUID walletId = UUID.randomUUID();
		UUID depositId = UUID.randomUUID();
		Money amount = Money.vnd(180_000);
		Wallet active = Wallet.open(walletId, UUID.randomUUID(), Currency.VND);
		Deposit peerConfirmed = Deposit.fromHistory(List.of(
				new DepositRequested(depositId, walletId, amount, "REF-CONT-1"),
				new DepositBankVerified(depositId),
				new DepositConfirmed(depositId)));

		when(depositRepository.load(depositId))
				.thenReturn(Optional.empty())
				.thenReturn(Optional.of(peerConfirmed));
		when(walletRepository.load(walletId)).thenReturn(Optional.of(active));
		when(bankReferenceStore.tryClaim(depositId, "REF-CONT-1", walletId, amount)).thenReturn(false);

		assertThat(handler.deposit(depositId, walletId, amount, "REF-CONT-1")).isEqualTo(DepositStatus.CONFIRMED);

		verify(depositRepository, never()).save(any());
		verifyNoInteractions(mockBankGateway, walletCommandHandler);
	}

	@Test
	void settleIfAlreadyVerifiedSettlesVerifiedOrPreV9CreditedAndReturnsFalseForPlainPending() {
		UUID walletId = UUID.randomUUID();
		UUID verifiedId = UUID.randomUUID();
		UUID plainPendingId = UUID.randomUUID();
		Money amount = Money.vnd(250_000);

		Deposit verified = Deposit.fromHistory(List.of(
				new DepositRequested(verifiedId, walletId, amount, "REF-S1"),
				new DepositBankVerified(verifiedId)));
		Deposit plainPending = Deposit.fromHistory(List.of(
				new DepositRequested(plainPendingId, walletId, amount, "REF-S2")));

		when(depositRepository.load(verifiedId)).thenReturn(Optional.of(verified));
		when(depositRepository.load(plainPendingId)).thenReturn(Optional.of(plainPending));
		when(walletCommandHandler.creditSettlement(walletId, verifiedId, amount,
				DepositCommandHandler.BANK_SETTLEMENT_ACCOUNT)).thenReturn(ApplyResult.APPLIED);

		assertThat(handler.settleIfAlreadyVerified(verifiedId, walletId, amount, "REF-S1")).isTrue();
		assertThat(verified.status()).isEqualTo(DepositStatus.CONFIRMED);
		assertThat(handler.settleIfAlreadyVerified(plainPendingId, walletId, amount, "REF-S2")).isFalse();
		verifyNoInteractions(mockBankGateway);
	}

	private static StoredEvent storedCredit(UUID depositId, UUID walletId, Money amount) {
		return new StoredEvent(Wallet.creditEventId(depositId, walletId), walletId, "Wallet", 2, "WalletCredited", 1,
				Instant.now(), depositId, null,
				new WalletCredited(walletId, amount, depositId, DepositCommandHandler.BANK_SETTLEMENT_ACCOUNT,
						AccountType.SETTLEMENT));
	}
}
