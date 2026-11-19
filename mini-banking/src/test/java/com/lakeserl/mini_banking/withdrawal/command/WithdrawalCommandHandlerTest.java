package com.lakeserl.mini_banking.withdrawal.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.deposit.command.DepositCommandHandler;
import com.lakeserl.mini_banking.mockbank.InMemoryMockBankGateway;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.PayoutStatus;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;
import com.lakeserl.mini_banking.wallet.domain.exceptions.ConflictingTransactionException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InsufficientFundsException;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;
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
	InMemoryMockBankGateway mockBank;

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
		// Wallet was debited (version 3) and then compensated by a reversal credit (version 4), restoring 1,000,000
		assertThat(walletRepository.load(walletId)).get().satisfies(w -> {
			assertThat(w.balance()).isEqualTo(Money.vnd(1_000_000));
			assertThat(w.committedVersion()).isEqualTo(4L);
		});
	}

	@Test
	void withdraw_whenBankTimesOut_marksFailedAndCompensatesWallet() {
		UUID walletId = openFundedWallet(500_000);
		UUID withdrawalId = UUID.randomUUID();
		mockBank.presetPayout(withdrawalId, PayoutStatus.TIMEOUT);

		WithdrawalStatus status = withdrawalHandler.withdraw(withdrawalId, walletId, Money.vnd(300_000), "970436-333");

		assertThat(status).isEqualTo(WithdrawalStatus.FAILED);
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

		assertThat(withdrawalHandler.withdraw(withdrawalId, walletId, Money.vnd(300_000), "970436-555"))
				.isEqualTo(WithdrawalStatus.CONFIRMED);
		assertThat(withdrawalHandler.withdraw(withdrawalId, walletId, Money.vnd(300_000), "970436-555"))
				.isEqualTo(WithdrawalStatus.CONFIRMED);
		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(700_000));

		assertThatThrownBy(() -> withdrawalHandler.withdraw(withdrawalId, walletId, Money.vnd(500_000), "970436-555"))
				.isInstanceOf(ConflictingTransactionException.class);
	}
}
