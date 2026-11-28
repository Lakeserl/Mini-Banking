package com.lakeserl.mini_banking.withdrawal.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.deposit.command.DepositCommandHandler;
import com.lakeserl.mini_banking.mockbank.InMemoryMockBankGateway;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.PayoutStatus;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;
import com.lakeserl.mini_banking.wallet.domain.exceptions.ConflictingTransactionException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InsufficientFundsException;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;
import com.lakeserl.mini_banking.withdrawal.command.WithdrawalCommandHandler.ReconcileOutcome;
import com.lakeserl.mini_banking.withdrawal.domain.WithdrawalStatus;
import com.lakeserl.mini_banking.withdrawal.infrastructure.WithdrawalRepository;

@PostgresDataTest
class WithdrawalCommandHandlerTest {

	@Autowired
	WithdrawalCommandHandler withdrawalHandler;

	@Autowired
	WithdrawalRepository withdrawalRepository;

	@Autowired
	DepositCommandHandler depositHandler;

	@Autowired
	WalletCommandHandler walletHandler;

	@Autowired
	WalletRepository walletRepository;

	@Autowired
	EventStore eventStore;

	@Autowired
	InMemoryMockBankGateway mockBank;

	@Autowired
	JdbcTemplate jdbc;

	@Value("${withdrawal.account-hash.pepper}")
	String pepper;

	@BeforeEach
	void resetBank() {
		mockBank.reset();
	}

	private UUID openFundedWallet(long initialVnd) {
		UUID walletId = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		depositHandler.deposit(UUID.randomUUID(), walletId, Money.vnd(initialVnd), "SEED-" + walletId);
		return walletId;
	}

	@Test
	void withdraw_whenBankSucceeds_debitsWalletAndConfirmsWithdrawal() {
		UUID walletId = openFundedWallet(1_000_000);
		UUID withdrawalId = UUID.randomUUID();

		WithdrawalStatus status = withdrawalHandler.withdraw(withdrawalId, walletId, Money.vnd(400_000), "970436-111");

		assertThat(status).isEqualTo(WithdrawalStatus.CONFIRMED);
		assertThat(withdrawalRepository.load(withdrawalId)).get().extracting(w -> w.status())
				.isEqualTo(WithdrawalStatus.CONFIRMED);
		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(600_000));
	}

	@Test
	void withdraw_whenBankRejects_debitsFirstThenCompensatesFullAmountBackToWallet() {
		UUID walletId = openFundedWallet(1_000_000);
		UUID withdrawalId = UUID.randomUUID();
		mockBank.presetPayout(withdrawalId, PayoutStatus.REJECTED);

		WithdrawalStatus status = withdrawalHandler.withdraw(withdrawalId, walletId, Money.vnd(400_000), "970436-222");

		assertThat(status).isEqualTo(WithdrawalStatus.FAILED);
		assertThat(withdrawalRepository.load(withdrawalId)).get().extracting(w -> w.status())
				.isEqualTo(WithdrawalStatus.FAILED);
		assertThat(walletRepository.load(walletId)).get().satisfies(w -> {
			assertThat(w.balance()).isEqualTo(Money.vnd(1_000_000));
			assertThat(w.committedVersion()).isEqualTo(4L);
		});
	}

	@Test
	void withdraw_whenBankTimesOut_staysPendingDebitedAndConvergesOnRetryAfterSettlement() {
		UUID walletId = openFundedWallet(500_000);
		UUID withdrawalId = UUID.randomUUID();
		mockBank.presetPayout(withdrawalId, PayoutStatus.TIMEOUT);

		WithdrawalStatus first = withdrawalHandler.withdraw(withdrawalId, walletId, Money.vnd(300_000), "970436-333");

		assertThat(first).isEqualTo(WithdrawalStatus.PENDING);
		assertThat(withdrawalRepository.load(withdrawalId)).get().extracting(w -> w.status())
				.isEqualTo(WithdrawalStatus.PENDING);
		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(200_000));

		// Bank subsequently settles to REJECTED -> retry marks FAILED and compensates wallet once
		mockBank.presetPayout(withdrawalId, PayoutStatus.REJECTED);
		WithdrawalStatus retried = withdrawalHandler.withdraw(withdrawalId, walletId, Money.vnd(300_000), "970436-333");

		assertThat(retried).isEqualTo(WithdrawalStatus.FAILED);
		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(500_000));
	}

	@Test
	void withdraw_whenInsufficientFunds_rejectsBeforeSavingWithdrawal() {
		UUID walletId = openFundedWallet(100_000);
		UUID withdrawalId = UUID.randomUUID();

		assertThatThrownBy(() -> withdrawalHandler.withdraw(withdrawalId, walletId, Money.vnd(250_000), "970436-444"))
				.isInstanceOf(InsufficientFundsException.class);

		assertThat(withdrawalRepository.load(withdrawalId)).isEmpty();
		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(100_000));
	}

	@Test
	void withdraw_retrySameParametersIsIdempotentAndConflictingParametersThrow() {
		UUID walletId = openFundedWallet(1_000_000);
		UUID withdrawalId = UUID.randomUUID();

		assertThat(withdrawalHandler.withdraw(withdrawalId, walletId, Money.vnd(300_000), "97043612345678"))
				.isEqualTo(WithdrawalStatus.CONFIRMED);
		assertThat(withdrawalHandler.withdraw(withdrawalId, walletId, Money.vnd(300_000), "97043612345678"))
				.isEqualTo(WithdrawalStatus.CONFIRMED);
		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(700_000));

		assertThatThrownBy(() -> withdrawalHandler.withdraw(withdrawalId, walletId, Money.vnd(500_000), "97043612345678"))
				.isInstanceOf(ConflictingTransactionException.class);
		assertThatThrownBy(() -> withdrawalHandler.withdraw(withdrawalId, walletId, Money.vnd(300_000), "11111112345678"))
				.isInstanceOf(ConflictingTransactionException.class);
	}

	@Test
	void twoIndependentHandlersRacingSameWithdrawalProtectEachLayerIndependently() throws Exception {
		UUID walletId = openFundedWallet(1_000_000);
		UUID withdrawalId = UUID.randomUUID();
		Money amount = Money.vnd(350_000);
		String rawAccount = "97043612345678";

		// Two separate handler instances (distinct JVM striped locks) sharing one Postgres DB & one MockBankGateway
		WithdrawalCommandHandler handlerA = new WithdrawalCommandHandler(
				withdrawalRepository, walletRepository, walletHandler, mockBank, eventStore, 3, pepper);
		WithdrawalCommandHandler handlerB = new WithdrawalCommandHandler(
				withdrawalRepository, walletRepository, walletHandler, mockBank, eventStore, 3, pepper);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch start = new CountDownLatch(1);
			Future<WithdrawalStatus> f1 = pool.submit(() -> {
				start.await(5, TimeUnit.SECONDS);
				return handlerA.withdraw(withdrawalId, walletId, amount, rawAccount);
			});
			Future<WithdrawalStatus> f2 = pool.submit(() -> {
				start.await(5, TimeUnit.SECONDS);
				return handlerB.withdraw(withdrawalId, walletId, amount, rawAccount);
			});
			start.countDown();
			assertThat(f1.get(10, TimeUnit.SECONDS)).isEqualTo(WithdrawalStatus.CONFIRMED);
			assertThat(f2.get(10, TimeUnit.SECONDS)).isEqualTo(WithdrawalStatus.CONFIRMED);
		} finally {
			pool.shutdownNow();
		}

		// Layer 1 (DB): exactly 1 WalletDebited event and balance reduced once
		Integer debitCount = jdbc.queryForObject(
				"SELECT COUNT(*) FROM events WHERE aggregate_id = ? AND event_type = 'WalletDebited'",
				Integer.class, walletId);
		assertThat(debitCount).isEqualTo(1);
		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(650_000));

		// Layer 2 (DB): exactly 1 WithdrawalRequested and 1 terminal Withdrawal event
		Integer requestedCount = jdbc.queryForObject(
				"SELECT COUNT(*) FROM events WHERE aggregate_id = ? AND event_type = 'WithdrawalRequested'",
				Integer.class, withdrawalId);
		assertThat(requestedCount).isEqualTo(1);
		Integer terminalCount = jdbc.queryForObject(
				"SELECT COUNT(*) FROM events WHERE aggregate_id = ? AND event_type IN ('WithdrawalConfirmed', 'WithdrawalFailed')",
				Integer.class, withdrawalId);
		assertThat(terminalCount).isEqualTo(1);

		// Layer 3 (Bank Gateway): exactly 1 settled payout record
		assertThat(mockBank.findPayout(withdrawalId)).get().satisfies(tx -> {
			assertThat(tx.status()).isEqualTo("SUCCESS");
			assertThat(tx.amount()).isEqualTo(amount);
		});
	}

	@Test
	void twoIndependentHandlersRacingRetryWithdrawAndReconcileConvergeWithoutDoubleCompensation() throws Exception {
		UUID walletId = openFundedWallet(800_000);
		UUID withdrawalId = UUID.randomUUID();
		Money amount = Money.vnd(300_000);
		String rawAccount = "97043612345678";

		mockBank.presetPayout(withdrawalId, PayoutStatus.TIMEOUT);
		assertThat(withdrawalHandler.withdraw(withdrawalId, walletId, amount, rawAccount))
				.isEqualTo(WithdrawalStatus.PENDING);
		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(500_000));

		// Bank asynchronously settles to REJECTED; race client retry on instance A against reconcile on instance B
		mockBank.recordPayoutTransaction(withdrawalId, amount, PayoutStatus.REJECTED, Instant.now());
		WithdrawalCommandHandler handlerA = new WithdrawalCommandHandler(
				withdrawalRepository, walletRepository, walletHandler, mockBank, eventStore, 3, pepper);
		WithdrawalCommandHandler handlerB = new WithdrawalCommandHandler(
				withdrawalRepository, walletRepository, walletHandler, mockBank, eventStore, 3, pepper);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch start = new CountDownLatch(1);
			Future<WithdrawalStatus> fRetry = pool.submit(() -> {
				start.await(5, TimeUnit.SECONDS);
				return handlerA.withdraw(withdrawalId, walletId, amount, rawAccount);
			});
			Future<ReconcileOutcome> fRecon = pool.submit(() -> {
				start.await(5, TimeUnit.SECONDS);
				return handlerB.reconcilePendingWithdrawal(withdrawalId);
			});
			start.countDown();
			assertThat(fRetry.get(10, TimeUnit.SECONDS)).isEqualTo(WithdrawalStatus.FAILED);
			assertThat(fRecon.get(10, TimeUnit.SECONDS)).isEqualTo(ReconcileOutcome.SETTLED);
		} finally {
			pool.shutdownNow();
		}

		// Wallet restored to exact 800,000 (initial deposit credit + 1 reversal credit = 2 WalletCredited events total)
		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(800_000));
		Integer creditCount = jdbc.queryForObject(
				"SELECT COUNT(*) FROM events WHERE aggregate_id = ? AND event_type = 'WalletCredited'",
				Integer.class, walletId);
		assertThat(creditCount).isEqualTo(2);
		assertThat(handlerA.ensureCompensatedIfFailed(withdrawalId)).isFalse();
	}
}
