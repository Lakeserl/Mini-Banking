package com.lakeserl.mini_banking.deposit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.deposit.domain.Deposit;
import com.lakeserl.mini_banking.deposit.domain.DepositStatus;
import com.lakeserl.mini_banking.deposit.domain.exceptions.DuplicateBankReferenceException;
import com.lakeserl.mini_banking.deposit.infrastructure.DepositRepository;
import com.lakeserl.mini_banking.mockbank.InMemoryMockBankGateway;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.VerificationStatus;
import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;
import com.lakeserl.mini_banking.wallet.domain.WalletStatus;
import com.lakeserl.mini_banking.wallet.domain.exceptions.ConflictingTransactionException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletLockedException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletNotFoundException;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;

@PostgresDataTest
class DepositCommandHandlerTest {

	@Autowired
	DepositCommandHandler depositHandler;

	@Autowired
	DepositRepository depositRepository;

	@Autowired
	WalletCommandHandler walletHandler;

	@Autowired
	WalletRepository walletRepository;

	@Autowired
	InMemoryMockBankGateway mockBank;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void resetBank() {
		mockBank.reset();
	}

	private static String uniqueRef(String prefix) {
		return prefix + "-" + UUID.randomUUID();
	}

	@Test
	void deposit_whenBankSucceeds_confirmsDepositAndCreditsWallet() {
		UUID walletId = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		UUID depositId = UUID.randomUUID();
		String ref = uniqueRef("BANK-OK");

		DepositStatus status = depositHandler.deposit(depositId, walletId, Money.vnd(500_000), ref);

		assertThat(status).isEqualTo(DepositStatus.CONFIRMED);
		Deposit loaded = depositRepository.load(depositId).orElseThrow();
		assertThat(loaded.status()).isEqualTo(DepositStatus.CONFIRMED);
		assertThat(loaded.isBankVerified()).isTrue();
		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(500_000));
	}

	@Test
	void deposit_whenBankRejects_marksFailedAndLeavesBalanceUnchanged() {
		UUID walletId = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		UUID depositId = UUID.randomUUID();
		String ref = uniqueRef("BANK-FAIL");
		mockBank.presetDeposit(ref, VerificationStatus.REJECTED);

		DepositStatus status = depositHandler.deposit(depositId, walletId, Money.vnd(500_000), ref);

		assertThat(status).isEqualTo(DepositStatus.FAILED);
		assertThat(depositRepository.load(depositId)).get().extracting(d -> d.status()).isEqualTo(DepositStatus.FAILED);
		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(0));
	}

	@Test
	void deposit_whenBankTimesOut_leavesDepositPendingAndDoesNotCreditWallet() {
		UUID walletId = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		UUID depositId = UUID.randomUUID();
		String ref = uniqueRef("BANK-TIMEOUT");
		mockBank.presetDeposit(ref, VerificationStatus.TIMEOUT);

		DepositStatus status = depositHandler.deposit(depositId, walletId, Money.vnd(500_000), ref);

		assertThat(status).isEqualTo(DepositStatus.PENDING);
		assertThat(depositRepository.load(depositId)).get().extracting(d -> d.status())
				.isEqualTo(DepositStatus.PENDING);
		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(0));
	}

	@Test
	void deposit_retrySameParametersIsIdempotentAndDifferentParametersThrowsConflict() {
		UUID walletId = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		UUID depositId = UUID.randomUUID();
		String ref = uniqueRef("BANK-RETRY");

		assertThat(depositHandler.deposit(depositId, walletId, Money.vnd(200_000), ref))
				.isEqualTo(DepositStatus.CONFIRMED);
		assertThat(depositHandler.deposit(depositId, walletId, Money.vnd(200_000), ref))
				.isEqualTo(DepositStatus.CONFIRMED);
		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(200_000));

		assertThatThrownBy(() -> depositHandler.deposit(depositId, walletId, Money.vnd(999_000), ref))
				.isInstanceOf(ConflictingTransactionException.class);
	}

	@Test
	void deposit_whenWalletDoesNotExist_throwsWalletNotFoundException() {
		assertThatThrownBy(() -> depositHandler.deposit(UUID.randomUUID(), UUID.randomUUID(), Money.vnd(100_000),
				uniqueRef("R")))
				.isInstanceOf(WalletNotFoundException.class);
	}

	@Test
	void newDepositOnLockedWalletThrowsWalletLockedAndClaimsNothing() {
		UUID walletId = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		walletHandler.lockWallet(walletId, "compliance hold");
		UUID depositId = UUID.randomUUID();
		String ref = uniqueRef("BANK-LOCK-NEW");

		assertThatThrownBy(() -> depositHandler.deposit(depositId, walletId, Money.vnd(100_000), ref))
				.isInstanceOf(WalletLockedException.class);

		Integer claims = jdbc.queryForObject(
				"SELECT count(*) FROM deposit_bank_references WHERE deposit_id = ?", Integer.class, depositId);
		assertThat(claims).isZero();
	}

	@Test
	void midFlightLockedWalletSettlesVerifiedDepositWhileStayingLocked() {
		UUID walletId = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		UUID depositId = UUID.randomUUID();
		String ref = uniqueRef("BANK-MIDLOCK");
		mockBank.presetDeposit(ref, VerificationStatus.TIMEOUT);

		assertThat(depositHandler.deposit(depositId, walletId, Money.vnd(350_000), ref))
				.isEqualTo(DepositStatus.PENDING);

		walletHandler.lockWallet(walletId, "fraud review");
		mockBank.presetDeposit(ref, VerificationStatus.SUCCESS);

		assertThat(depositHandler.deposit(depositId, walletId, Money.vnd(350_000), ref))
				.isEqualTo(DepositStatus.CONFIRMED);

		var wallet = walletRepository.load(walletId).orElseThrow();
		assertThat(wallet.balance()).isEqualTo(Money.vnd(350_000));
		assertThat(wallet.status()).isEqualTo(WalletStatus.LOCKED);
		assertThatThrownBy(() -> walletHandler.debit(walletId, UUID.randomUUID(), Money.vnd(50_000),
				"TRANSFER_CLEARING", AccountType.CLEARING))
				.isInstanceOf(WalletLockedException.class);
	}

	@Test
	void failedDepositBankReferenceCannotBeReusedEvenWithDifferentCase() {
		UUID walletId = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		String ref = uniqueRef("BANK-NOREUSE");
		mockBank.presetDeposit(ref, VerificationStatus.REJECTED);

		assertThat(depositHandler.deposit(UUID.randomUUID(), walletId, Money.vnd(100_000), ref))
				.isEqualTo(DepositStatus.FAILED);

		assertThatThrownBy(() -> depositHandler.deposit(UUID.randomUUID(), walletId, Money.vnd(100_000), ref))
				.isInstanceOf(DuplicateBankReferenceException.class);
		assertThatThrownBy(() -> depositHandler.deposit(UUID.randomUUID(), walletId, Money.vnd(100_000), ref.toLowerCase()))
				.isInstanceOf(DuplicateBankReferenceException.class);
	}

	@Test
	void concurrentDepositsWithSameCaseInsensitiveBankReferenceAllowOnlyOne() throws Exception {
		UUID walletId = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		String upperRef = uniqueRef("BANK-RACE").toUpperCase();
		String lowerRef = upperRef.toLowerCase();

		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<Object>> futures = new ArrayList<>();
			for (String ref : List.of(upperRef, lowerRef)) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					start.await();
					try {
						return depositHandler.deposit(UUID.randomUUID(), walletId, Money.vnd(250_000), ref);
					} catch (DuplicateBankReferenceException ex) {
						return ex;
					}
				}));
			}
			ready.await();
			start.countDown();

			List<Object> outcomes = List.of(futures.get(0).get(), futures.get(1).get());
			assertThat(outcomes.stream().filter(DepositStatus.CONFIRMED::equals).count()).isEqualTo(1);
			assertThat(outcomes.stream().filter(DuplicateBankReferenceException.class::isInstance).count()).isEqualTo(1);
			assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(250_000));
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void settleIfAlreadyVerifiedConvergesVerifiedDepositWithoutBankCall() {
		UUID walletId = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		UUID depositId = UUID.randomUUID();
		String ref = uniqueRef("BANK-RECON");
		Deposit d = Deposit.request(depositId, walletId, Money.vnd(175_000), ref);
		d.markBankVerified();
		depositRepository.save(d);

		mockBank.presetDeposit(ref, VerificationStatus.REJECTED);

		assertThat(depositHandler.settleIfAlreadyVerified(depositId, walletId, Money.vnd(175_000), ref)).isTrue();
		assertThat(depositRepository.load(depositId)).get().extracting(Deposit::status).isEqualTo(DepositStatus.CONFIRMED);
		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(175_000));
	}
}
