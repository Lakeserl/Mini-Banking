package com.lakeserl.mini_banking.deposit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.deposit.domain.DepositStatus;
import com.lakeserl.mini_banking.deposit.infrastructure.DepositRepository;
import com.lakeserl.mini_banking.mockbank.InMemoryMockBankGateway;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.VerificationStatus;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;
import com.lakeserl.mini_banking.wallet.domain.exceptions.ConflictingTransactionException;
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

	@BeforeEach
	void resetBank() {
		mockBank.reset();
	}

	@Test
	void deposit_whenBankSucceeds_confirmsDepositAndCreditsWallet() {
		UUID walletId = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		UUID depositId = UUID.randomUUID();

		DepositStatus status = depositHandler.deposit(depositId, walletId, Money.vnd(500_000), "BANK-OK-1");

		assertThat(status).isEqualTo(DepositStatus.CONFIRMED);
		assertThat(depositRepository.load(depositId)).get().extracting(d -> d.status())
				.isEqualTo(DepositStatus.CONFIRMED);
		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(500_000));
	}

	@Test
	void deposit_whenBankRejects_marksFailedAndLeavesBalanceUnchanged() {
		UUID walletId = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		UUID depositId = UUID.randomUUID();
		mockBank.presetDeposit("BANK-FAIL-1", VerificationStatus.REJECTED);

		DepositStatus status = depositHandler.deposit(depositId, walletId, Money.vnd(500_000), "BANK-FAIL-1");

		assertThat(status).isEqualTo(DepositStatus.FAILED);
		assertThat(depositRepository.load(depositId)).get().extracting(d -> d.status()).isEqualTo(DepositStatus.FAILED);
		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(0));
	}

	@Test
	void deposit_whenBankTimesOut_leavesDepositPendingAndDoesNotCreditWallet() {
		UUID walletId = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		UUID depositId = UUID.randomUUID();
		mockBank.presetDeposit("BANK-TIMEOUT-1", VerificationStatus.TIMEOUT);

		DepositStatus status = depositHandler.deposit(depositId, walletId, Money.vnd(500_000), "BANK-TIMEOUT-1");

		assertThat(status).isEqualTo(DepositStatus.PENDING);
		assertThat(depositRepository.load(depositId)).get().extracting(d -> d.status())
				.isEqualTo(DepositStatus.PENDING);
		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(0));
	}

	@Test
	void deposit_retrySameParametersIsIdempotentAndDifferentParametersThrowsConflict() {
		UUID walletId = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		UUID depositId = UUID.randomUUID();

		assertThat(depositHandler.deposit(depositId, walletId, Money.vnd(200_000), "BANK-RETRY-1"))
				.isEqualTo(DepositStatus.CONFIRMED);
		assertThat(depositHandler.deposit(depositId, walletId, Money.vnd(200_000), "BANK-RETRY-1"))
				.isEqualTo(DepositStatus.CONFIRMED);
		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(200_000));

		assertThatThrownBy(() -> depositHandler.deposit(depositId, walletId, Money.vnd(999_000), "BANK-RETRY-1"))
				.isInstanceOf(ConflictingTransactionException.class);
	}

	@Test
	void deposit_whenWalletDoesNotExist_throwsWalletNotFoundException() {
		assertThatThrownBy(() -> depositHandler.deposit(UUID.randomUUID(), UUID.randomUUID(), Money.vnd(100_000), "R"))
				.isInstanceOf(WalletNotFoundException.class);
	}
}
