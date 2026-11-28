package com.lakeserl.mini_banking.withdrawal.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.lakeserl.mini_banking.mockbank.InMemoryMockBankGateway;
import com.lakeserl.mini_banking.mockbank.MockBankGateway;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.BankTransaction;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.PayoutResult;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.PayoutStatus;
import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.domain.events.WalletCredited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletDebited;
import com.lakeserl.mini_banking.wallet.domain.exceptions.ConflictingTransactionException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InsufficientFundsException;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;
import com.lakeserl.mini_banking.withdrawal.command.WithdrawalCommandHandler.ReconcileOutcome;
import com.lakeserl.mini_banking.withdrawal.domain.Withdrawal;
import com.lakeserl.mini_banking.withdrawal.domain.WithdrawalStatus;
import com.lakeserl.mini_banking.withdrawal.infrastructure.WithdrawalRepository;

class WithdrawalCommandHandlerUnitTest {

	private static final String TEST_PEPPER = "unit-test-withdrawal-pepper-at-least-32-bytes-01";

	private WithdrawalRepository withdrawalRepository;
	private WalletRepository walletRepository;
	private WalletCommandHandler walletCommandHandler;
	private MockBankGateway mockBankGateway;
	private EventStore eventStore;
	private WithdrawalCommandHandler handler;

	@BeforeEach
	void setUp() {
		withdrawalRepository = mock(WithdrawalRepository.class);
		walletRepository = mock(WalletRepository.class);
		walletCommandHandler = mock(WalletCommandHandler.class);
		mockBankGateway = mock(MockBankGateway.class);
		eventStore = mock(EventStore.class);
		when(mockBankGateway.findPayout(any())).thenReturn(Optional.empty());
		when(eventStore.findEvent(any())).thenReturn(Optional.empty());
		handler = new WithdrawalCommandHandler(withdrawalRepository, walletRepository, walletCommandHandler,
				mockBankGateway, eventStore, 3, TEST_PEPPER);
	}

	private Wallet activeFundedWallet(UUID walletId, long balanceVnd) {
		Wallet wallet = Wallet.open(walletId, UUID.randomUUID(), Currency.VND);
		if (balanceVnd > 0) {
			wallet.credit(UUID.randomUUID(), Money.vnd(balanceVnd), "BANK_SETTLEMENT", AccountType.SETTLEMENT);
		}
		wallet.markCommitted();
		return wallet;
	}

	private void wireInMemoryWithdrawalStore(UUID withdrawalId, AtomicReference<Withdrawal> stored) {
		when(withdrawalRepository.load(withdrawalId)).thenAnswer(inv -> {
			Withdrawal w = stored.get();
			if (w == null) {
				return Optional.empty();
			}
			return Optional.of(w);
		});
		doAnswer(inv -> {
			Withdrawal w = inv.getArgument(0);
			w.markCommitted();
			stored.set(w);
			return null;
		}).when(withdrawalRepository).save(any(Withdrawal.class));
	}

	@Test
	void constructorRejectsShortPepperIncludingBackupPlaceholder() {
		assertThatThrownBy(() -> new WithdrawalCommandHandler(withdrawalRepository, walletRepository,
				walletCommandHandler, mockBankGateway, eventStore, 3, "backup-unused"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void withdrawPersistsFirstPassesRawAccountToBankAndCompensatesOnRejected() {
		UUID withdrawalId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Money amount = Money.vnd(250_000);
		String rawAccount = "97043612345678";
		AtomicReference<Withdrawal> stored = new AtomicReference<>();
		wireInMemoryWithdrawalStore(withdrawalId, stored);

		when(walletRepository.load(walletId)).thenReturn(Optional.of(activeFundedWallet(walletId, 500_000)));
		UUID debitEventId = Wallet.debitEventId(withdrawalId, walletId);
		AtomicBoolean debited = new AtomicBoolean(false);
		when(eventStore.contains(debitEventId)).thenAnswer(inv -> debited.get());
		doAnswer(inv -> {
			debited.set(true);
			return WalletCommandHandler.ApplyResult.APPLIED;
		}).when(walletCommandHandler).debit(eq(walletId), eq(withdrawalId), eq(amount), any(), any());
		when(mockBankGateway.payout(withdrawalId, amount, rawAccount)).thenReturn(PayoutResult.rejected("declined"));

		WithdrawalStatus status = handler.withdraw(withdrawalId, walletId, amount, rawAccount);

		assertThat(status).isEqualTo(WithdrawalStatus.FAILED);
		verify(mockBankGateway).payout(withdrawalId, amount, rawAccount);
		verify(walletCommandHandler).debit(
				eq(walletId), eq(withdrawalId), eq(amount),
				eq(WithdrawalCommandHandler.BANK_SETTLEMENT_ACCOUNT), eq(AccountType.SETTLEMENT));
		verify(walletCommandHandler).creditCompensation(
				eq(walletId), eq(Withdrawal.reversalTransactionId(withdrawalId)), eq(amount),
				eq(WithdrawalCommandHandler.BANK_SETTLEMENT_ACCOUNT), eq(AccountType.SETTLEMENT));
		verify(walletCommandHandler, never()).credit(any(), any(), any(), any(), any());

		ArgumentCaptor<Withdrawal> saved = ArgumentCaptor.forClass(Withdrawal.class);
		verify(withdrawalRepository, atLeastOnce()).save(saved.capture());
		assertThat(saved.getValue().bankAccount()).isEqualTo("****5678");
		assertThat(saved.getValue().bankAccountHash()).startsWith("v1:");
	}

	@Test
	void initialPersistFailureAbortsBeforeDebitOrCompensation() {
		UUID withdrawalId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Money amount = Money.vnd(300_000);

		when(withdrawalRepository.load(withdrawalId)).thenReturn(Optional.empty());
		when(walletRepository.load(walletId)).thenReturn(Optional.of(activeFundedWallet(walletId, 500_000)));
		doThrow(new RuntimeException("db connection lost")).when(withdrawalRepository).save(any(Withdrawal.class));

		assertThatThrownBy(() -> handler.withdraw(withdrawalId, walletId, amount, "97043612345678"))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("db connection lost");

		verify(walletCommandHandler, never()).debit(any(), any(), any(), any(), any());
		verify(walletCommandHandler, never()).creditCompensation(any(), any(), any(), any(), any());
		verify(mockBankGateway, never()).payout(any(), any(), any());
	}

	@Test
	void retryWithDifferentAccountSharingLastFourDigitsThrowsConflictingTransaction() {
		UUID withdrawalId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Money amount = Money.vnd(100_000);
		Withdrawal existing = Withdrawal.request(withdrawalId, walletId, amount, "1111115678", TEST_PEPPER);
		existing.confirm("BANK-PAY-1");
		existing.markCommitted();
		when(withdrawalRepository.load(withdrawalId)).thenReturn(Optional.of(existing));

		assertThat(handler.withdraw(withdrawalId, walletId, amount, "1111115678"))
				.isEqualTo(WithdrawalStatus.CONFIRMED);
		assertThatThrownBy(() -> handler.withdraw(withdrawalId, walletId, amount, "9999995678"))
				.isInstanceOf(ConflictingTransactionException.class);
	}

	@Test
	void legacyNullHashWithdrawalMatchesMaskedAccountAndRejectsDifferentLastFour() {
		UUID withdrawalId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Money amount = Money.vnd(100_000);
		Withdrawal legacy = Withdrawal.request(withdrawalId, walletId, amount, "97043612345678");
		legacy.fail("old decline");
		legacy.markCommitted();
		assertThat(legacy.bankAccountHash()).isNull();

		UUID debitEventId = Wallet.debitEventId(withdrawalId, walletId);
		when(withdrawalRepository.load(withdrawalId)).thenReturn(Optional.of(legacy));
		when(eventStore.contains(debitEventId)).thenReturn(true);

		assertThat(handler.withdraw(withdrawalId, walletId, amount, "97043612345678"))
				.isEqualTo(WithdrawalStatus.FAILED);
		verify(walletCommandHandler).creditCompensation(
				eq(walletId), eq(Withdrawal.reversalTransactionId(withdrawalId)), eq(amount),
				eq(WithdrawalCommandHandler.BANK_SETTLEMENT_ACCOUNT), eq(AccountType.SETTLEMENT));

		assertThatThrownBy(() -> handler.withdraw(withdrawalId, walletId, amount, "97043612349999"))
				.isInstanceOf(ConflictingTransactionException.class);
	}

	@Test
	void legacyDebitedOrphanWithZeroRemainingBalanceStillPersistsAndCallsPayout() {
		UUID withdrawalId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Money amount = Money.vnd(300_000);
		String rawAccount = "97043612345678";
		AtomicReference<Withdrawal> stored = new AtomicReference<>();
		wireInMemoryWithdrawalStore(withdrawalId, stored);

		UUID debitEventId = Wallet.debitEventId(withdrawalId, walletId);
		WalletDebited orphanDebit = new WalletDebited(walletId, amount, withdrawalId,
				WithdrawalCommandHandler.BANK_SETTLEMENT_ACCOUNT, AccountType.SETTLEMENT);
		when(eventStore.findEvent(debitEventId)).thenReturn(Optional.of(new StoredEvent(
				debitEventId, walletId, "Wallet", 3L, "WalletDebited", 1, Instant.now(), withdrawalId, null,
				orphanDebit)));
		when(eventStore.contains(debitEventId)).thenReturn(true);
		when(walletRepository.load(walletId)).thenReturn(Optional.of(activeFundedWallet(walletId, 0)));
		when(mockBankGateway.payout(withdrawalId, amount, rawAccount)).thenReturn(PayoutResult.success("BANK-PAY-ORPHAN"));

		WithdrawalStatus status = handler.withdraw(withdrawalId, walletId, amount, rawAccount);

		assertThat(status).isEqualTo(WithdrawalStatus.CONFIRMED);
		verify(walletCommandHandler, never()).debit(any(), any(), any(), any(), any());
		verify(mockBankGateway).payout(withdrawalId, amount, rawAccount);
	}

	@Test
	void legacyOrphanReversalValidatesAmountBeforeReturningFailed() {
		UUID withdrawalId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Money recordedAmount = Money.vnd(300_000);
		UUID reversalEventId = Wallet.creditEventId(Withdrawal.reversalTransactionId(withdrawalId), walletId);
		WalletCredited reversal = new WalletCredited(walletId, recordedAmount,
				Withdrawal.reversalTransactionId(withdrawalId),
				WithdrawalCommandHandler.BANK_SETTLEMENT_ACCOUNT, AccountType.SETTLEMENT);

		when(withdrawalRepository.load(withdrawalId)).thenReturn(Optional.empty());
		when(eventStore.findEvent(reversalEventId)).thenReturn(Optional.of(new StoredEvent(
				reversalEventId, walletId, "Wallet", 4L, "WalletCredited", 1, Instant.now(),
				Withdrawal.reversalTransactionId(withdrawalId), null, reversal)));

		assertThat(handler.withdraw(withdrawalId, walletId, recordedAmount, "97043612345678"))
				.isEqualTo(WithdrawalStatus.FAILED);
		verify(walletCommandHandler, never()).debit(any(), any(), any(), any(), any());
		verify(mockBankGateway, never()).payout(any(), any(), any());

		assertThatThrownBy(() -> handler.withdraw(withdrawalId, walletId, Money.vnd(500_000), "97043612345678"))
				.isInstanceOf(ConflictingTransactionException.class);
	}

	@Test
	void bankTimeoutLeavesWithdrawalPendingWithoutCompensatingWallet() {
		UUID withdrawalId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Money amount = Money.vnd(200_000);
		AtomicReference<Withdrawal> stored = new AtomicReference<>();
		wireInMemoryWithdrawalStore(withdrawalId, stored);

		when(walletRepository.load(walletId)).thenReturn(Optional.of(activeFundedWallet(walletId, 500_000)));
		AtomicBoolean debited = new AtomicBoolean(false);
		when(eventStore.contains(Wallet.debitEventId(withdrawalId, walletId))).thenAnswer(inv -> debited.get());
		doAnswer(inv -> {
			debited.set(true);
			return WalletCommandHandler.ApplyResult.APPLIED;
		}).when(walletCommandHandler).debit(eq(walletId), eq(withdrawalId), eq(amount), any(), any());
		when(mockBankGateway.payout(withdrawalId, amount, "97043612345678")).thenReturn(PayoutResult.timeout());

		WithdrawalStatus status = handler.withdraw(withdrawalId, walletId, amount, "97043612345678");

		assertThat(status).isEqualTo(WithdrawalStatus.PENDING);
		assertThat(stored.get().status()).isEqualTo(WithdrawalStatus.PENDING);
		verify(walletCommandHandler, never()).creditCompensation(any(), any(), any(), any(), any());
	}

	@Test
	void continueExistingConsultsFindPayoutBeforeCallingPayoutAndValidatesInvariants() {
		UUID withdrawalId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Money amount = Money.vnd(200_000);
		String rawAccount = "97043612345678";
		Withdrawal pending = Withdrawal.request(withdrawalId, walletId, amount, rawAccount, TEST_PEPPER);
		pending.markCommitted();
		AtomicReference<Withdrawal> stored = new AtomicReference<>(pending);
		wireInMemoryWithdrawalStore(withdrawalId, stored);
		when(eventStore.contains(Wallet.debitEventId(withdrawalId, walletId))).thenReturn(true);

		// Case 1: findPayout already reports terminal SUCCESS -> settle without calling payout() again
		when(mockBankGateway.findPayout(withdrawalId)).thenReturn(Optional.of(new BankTransaction(
				"BANK-PAY-" + withdrawalId, "PAYOUT", withdrawalId, amount, "SUCCESS", null, Instant.now())));

		assertThat(handler.withdraw(withdrawalId, walletId, amount, rawAccount))
				.isEqualTo(WithdrawalStatus.CONFIRMED);
		verify(mockBankGateway, never()).payout(any(), any(), any());

		// Case 2: findPayout reports mismatched amount -> fail loudly without calling payout()
		UUID badId = UUID.randomUUID();
		Withdrawal badPending = Withdrawal.request(badId, walletId, amount, rawAccount, TEST_PEPPER);
		badPending.markCommitted();
		AtomicReference<Withdrawal> badStored = new AtomicReference<>(badPending);
		wireInMemoryWithdrawalStore(badId, badStored);
		when(eventStore.contains(Wallet.debitEventId(badId, walletId))).thenReturn(true);
		when(mockBankGateway.findPayout(badId)).thenReturn(Optional.of(new BankTransaction(
				"BANK-PAY-" + badId, "PAYOUT", badId, Money.vnd(999_999), "SUCCESS", null, Instant.now())));

		assertThatThrownBy(() -> handler.withdraw(badId, walletId, amount, rawAccount))
				.isInstanceOf(IllegalStateException.class);
		verify(mockBankGateway, never()).payout(eq(badId), any(), any());

		// Case 3: findPayout still TIMEOUT -> calls payout()
		UUID retryId = UUID.randomUUID();
		Withdrawal retryPending = Withdrawal.request(retryId, walletId, amount, rawAccount, TEST_PEPPER);
		retryPending.markCommitted();
		AtomicReference<Withdrawal> retryStored = new AtomicReference<>(retryPending);
		wireInMemoryWithdrawalStore(retryId, retryStored);
		when(eventStore.contains(Wallet.debitEventId(retryId, walletId))).thenReturn(true);
		when(mockBankGateway.findPayout(retryId)).thenReturn(Optional.of(new BankTransaction(
				"BANK-PAY-" + retryId, "PAYOUT", retryId, amount, "TIMEOUT", "timeout", Instant.now())));
		when(mockBankGateway.payout(retryId, amount, rawAccount))
				.thenReturn(PayoutResult.success("BANK-PAY-" + retryId));

		assertThat(handler.withdraw(retryId, walletId, amount, rawAccount))
				.isEqualTo(WithdrawalStatus.CONFIRMED);
		verify(mockBankGateway, times(1)).payout(retryId, amount, rawAccount);
	}

	@Test
	void preDebitRaceFailureMarksFailedWithoutCompensatingAndNeverMintsFundsOnRetry() {
		UUID withdrawalId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Money amount = Money.vnd(400_000);
		String rawAccount = "97043612345678";
		AtomicReference<Withdrawal> stored = new AtomicReference<>();
		wireInMemoryWithdrawalStore(withdrawalId, stored);

		when(walletRepository.load(walletId)).thenReturn(Optional.of(activeFundedWallet(walletId, 500_000)));
		when(eventStore.contains(Wallet.debitEventId(withdrawalId, walletId))).thenReturn(false);
		doThrow(new InsufficientFundsException("Insufficient funds")).when(walletCommandHandler)
				.debit(eq(walletId), eq(withdrawalId), eq(amount), any(), any());

		assertThatThrownBy(() -> handler.withdraw(withdrawalId, walletId, amount, rawAccount))
				.isInstanceOf(InsufficientFundsException.class);

		assertThat(stored.get().status()).isEqualTo(WithdrawalStatus.FAILED);
		verify(walletCommandHandler, never()).creditCompensation(any(), any(), any(), any(), any());

		// Retrying the failed pre-debit withdrawal MUST NOT call creditCompensation
		assertThat(handler.withdraw(withdrawalId, walletId, amount, rawAccount))
				.isEqualTo(WithdrawalStatus.FAILED);
		verify(walletCommandHandler, never()).creditCompensation(any(), any(), any(), any(), any());
	}

	@Test
	void concurrentWithdrawalsWithSameIdNeverDoubleSpendOrDoublePayout() throws Exception {
		UUID withdrawalId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Money amount = Money.vnd(150_000);
		String rawAccount = "97043612345678";
		AtomicReference<Withdrawal> stored = new AtomicReference<>();
		wireInMemoryWithdrawalStore(withdrawalId, stored);

		when(walletRepository.load(walletId)).thenReturn(Optional.of(activeFundedWallet(walletId, 500_000)));
		AtomicBoolean debited = new AtomicBoolean(false);
		when(eventStore.contains(Wallet.debitEventId(withdrawalId, walletId))).thenAnswer(inv -> debited.get());
		doAnswer(inv -> {
			debited.set(true);
			return WalletCommandHandler.ApplyResult.APPLIED;
		}).when(walletCommandHandler).debit(eq(walletId), eq(withdrawalId), eq(amount), any(), any());
		CountDownLatch enteredPayout = new CountDownLatch(1);
		when(mockBankGateway.payout(withdrawalId, amount, rawAccount)).thenAnswer(inv -> {
			enteredPayout.countDown();
			Thread.sleep(50);
			return PayoutResult.success("BANK-PAY-" + withdrawalId);
		});

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch start = new CountDownLatch(1);
			Future<WithdrawalStatus> f1 = pool.submit(() -> {
				start.await(5, TimeUnit.SECONDS);
				return handler.withdraw(withdrawalId, walletId, amount, rawAccount);
			});
			Future<WithdrawalStatus> f2 = pool.submit(() -> {
				start.await(5, TimeUnit.SECONDS);
				return handler.withdraw(withdrawalId, walletId, amount, rawAccount);
			});
			start.countDown();
			assertThat(f1.get(5, TimeUnit.SECONDS)).isEqualTo(WithdrawalStatus.CONFIRMED);
			assertThat(f2.get(5, TimeUnit.SECONDS)).isEqualTo(WithdrawalStatus.CONFIRMED);
		} finally {
			pool.shutdownNow();
		}

		verify(walletCommandHandler, times(1)).debit(eq(walletId), eq(withdrawalId), eq(amount), any(), any());
		verify(mockBankGateway, times(1)).payout(withdrawalId, amount, rawAccount);
	}

	@Test
	void reconcilePendingWithdrawalSettlesConfirmedRejectedAbandonedAndFlagsUnresolvedOrMismatch() {
		UUID walletId = UUID.randomUUID();
		Money amount = Money.vnd(100_000);

		// 1. Pre-debit abandoned -> FAILED without compensation -> SETTLED
		UUID abandonedId = UUID.randomUUID();
		Withdrawal abandoned = Withdrawal.request(abandonedId, walletId, amount, "97043612345678", TEST_PEPPER);
		abandoned.markCommitted();
		AtomicReference<Withdrawal> abandonedStore = new AtomicReference<>(abandoned);
		wireInMemoryWithdrawalStore(abandonedId, abandonedStore);
		when(eventStore.contains(Wallet.debitEventId(abandonedId, walletId))).thenReturn(false);

		assertThat(handler.reconcilePendingWithdrawal(abandonedId)).isEqualTo(ReconcileOutcome.SETTLED);
		assertThat(abandonedStore.get().status()).isEqualTo(WithdrawalStatus.FAILED);
		verify(walletCommandHandler, never()).creditCompensation(eq(walletId),
				eq(Withdrawal.reversalTransactionId(abandonedId)), any(), any(), any());

		// 2. Bank REJECTED with blank reason -> sanitized reason + compensated -> SETTLED
		UUID rejId = UUID.randomUUID();
		Withdrawal rej = Withdrawal.request(rejId, walletId, amount, "97043612345678", TEST_PEPPER);
		rej.markCommitted();
		AtomicReference<Withdrawal> rejStore = new AtomicReference<>(rej);
		wireInMemoryWithdrawalStore(rejId, rejStore);
		when(eventStore.contains(Wallet.debitEventId(rejId, walletId))).thenReturn(true);
		when(mockBankGateway.findPayout(rejId)).thenReturn(Optional.of(new BankTransaction(
				"BANK-PAY-" + rejId, "PAYOUT", rejId, amount, "REJECTED", "   ", Instant.now())));

		assertThat(handler.reconcilePendingWithdrawal(rejId)).isEqualTo(ReconcileOutcome.SETTLED);
		assertThat(rejStore.get().status()).isEqualTo(WithdrawalStatus.FAILED);
		assertThat(rejStore.get().failureReason()).isEqualTo("Bank rejected withdrawal");
		verify(walletCommandHandler).creditCompensation(eq(walletId),
				eq(Withdrawal.reversalTransactionId(rejId)), eq(amount), any(), any());

		// 3. Bank SUCCESS with blank bankReference -> MISMATCH
		UUID blankRefId = UUID.randomUUID();
		Withdrawal blankRef = Withdrawal.request(blankRefId, walletId, amount, "97043612345678", TEST_PEPPER);
		blankRef.markCommitted();
		when(withdrawalRepository.load(blankRefId)).thenReturn(Optional.of(blankRef));
		when(eventStore.contains(Wallet.debitEventId(blankRefId, walletId))).thenReturn(true);
		when(mockBankGateway.findPayout(blankRefId)).thenReturn(Optional.of(new BankTransaction(
				"  ", "PAYOUT", blankRefId, amount, "SUCCESS", null, Instant.now())));

		assertThat(handler.reconcilePendingWithdrawal(blankRefId)).isEqualTo(ReconcileOutcome.MISMATCH);

		// 4. Bank still TIMEOUT -> UNRESOLVED
		UUID timeoutId = UUID.randomUUID();
		Withdrawal timedOut = Withdrawal.request(timeoutId, walletId, amount, "97043612345678", TEST_PEPPER);
		timedOut.markCommitted();
		when(withdrawalRepository.load(timeoutId)).thenReturn(Optional.of(timedOut));
		when(eventStore.contains(Wallet.debitEventId(timeoutId, walletId))).thenReturn(true);
		when(mockBankGateway.findPayout(timeoutId)).thenReturn(Optional.of(new BankTransaction(
				"BANK-PAY-" + timeoutId, "PAYOUT", timeoutId, amount, "TIMEOUT", "timeout", Instant.now())));

		assertThat(handler.reconcilePendingWithdrawal(timeoutId)).isEqualTo(ReconcileOutcome.UNRESOLVED);
	}

	@Test
	void ensureCompensatedIfFailedCompensatesOnlyWhenDebitedAndUncompensated() {
		UUID walletId = UUID.randomUUID();
		Money amount = Money.vnd(100_000);

		UUID needsCompId = UUID.randomUUID();
		Withdrawal needsComp = Withdrawal.request(needsCompId, walletId, amount, "97043612345678", TEST_PEPPER);
		needsComp.fail("declined");
		needsComp.markCommitted();
		when(withdrawalRepository.load(needsCompId)).thenReturn(Optional.of(needsComp));
		when(eventStore.contains(Wallet.debitEventId(needsCompId, walletId))).thenReturn(true);
		when(eventStore.contains(Wallet.creditEventId(Withdrawal.reversalTransactionId(needsCompId), walletId)))
				.thenReturn(false);

		assertThat(handler.ensureCompensatedIfFailed(needsCompId)).isTrue();
		verify(walletCommandHandler).creditCompensation(eq(walletId),
				eq(Withdrawal.reversalTransactionId(needsCompId)), eq(amount), any(), any());

		UUID preDebitFailedId = UUID.randomUUID();
		Withdrawal preDebitFailed = Withdrawal.request(preDebitFailedId, walletId, amount, "97043612345678", TEST_PEPPER);
		preDebitFailed.fail("Insufficient funds");
		preDebitFailed.markCommitted();
		when(withdrawalRepository.load(preDebitFailedId)).thenReturn(Optional.of(preDebitFailed));
		when(eventStore.contains(Wallet.debitEventId(preDebitFailedId, walletId))).thenReturn(false);

		assertThat(handler.ensureCompensatedIfFailed(preDebitFailedId)).isFalse();
		verify(walletCommandHandler, never()).creditCompensation(eq(walletId),
				eq(Withdrawal.reversalTransactionId(preDebitFailedId)), any(), any(), any());
	}

	@Test
	void inMemoryMockBankGatewayPayoutIsIdempotentForTerminalAndAllowsRetryAfterTimeout() {
		InMemoryMockBankGateway gateway = new InMemoryMockBankGateway();
		UUID id = UUID.randomUUID();
		Money amount = Money.vnd(100_000);

		// TIMEOUT is NOT frozen -> subsequent retry after preset change returns SUCCESS
		gateway.presetPayout(id, PayoutStatus.TIMEOUT);
		assertThat(gateway.payout(id, amount, "97043612345678").status()).isEqualTo(PayoutStatus.TIMEOUT);

		gateway.presetPayout(id, PayoutStatus.SUCCESS);
		PayoutResult settled = gateway.payout(id, amount, "97043612345678");
		assertThat(settled.status()).isEqualTo(PayoutStatus.SUCCESS);
		assertThat(gateway.findPayout(id)).get().extracting(BankTransaction::status).isEqualTo("SUCCESS");

		// Once terminal (SUCCESS), changing preset to REJECTED must still return frozen SUCCESS
		gateway.presetPayout(id, PayoutStatus.REJECTED);
		PayoutResult repeated = gateway.payout(id, amount, "97043612345678");
		assertThat(repeated.status()).isEqualTo(PayoutStatus.SUCCESS);
		assertThat(repeated.bankReference()).isEqualTo(settled.bankReference());
	}
}
