package com.lakeserl.mini_banking.deposit.command;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.lakeserl.mini_banking.deposit.domain.Deposit;
import com.lakeserl.mini_banking.deposit.domain.DepositStatus;
import com.lakeserl.mini_banking.deposit.infrastructure.DepositRepository;
import com.lakeserl.mini_banking.mockbank.MockBankGateway;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.DepositVerificationResult;
import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;
import com.lakeserl.mini_banking.wallet.domain.exceptions.ConflictingTransactionException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletNotFoundException;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;

@Service
public class DepositCommandHandler {

	public static final String BANK_SETTLEMENT_ACCOUNT = "BANK_SETTLEMENT";

	private final DepositRepository depositRepository;
	private final WalletRepository walletRepository;
	private final WalletCommandHandler walletCommandHandler;
	private final MockBankGateway mockBankGateway;

	public DepositCommandHandler(DepositRepository depositRepository, WalletRepository walletRepository,
			WalletCommandHandler walletCommandHandler, MockBankGateway mockBankGateway) {
		this.depositRepository = Objects.requireNonNull(depositRepository, "depositRepository must not be null");
		this.walletRepository = Objects.requireNonNull(walletRepository, "walletRepository must not be null");
		this.walletCommandHandler = Objects.requireNonNull(walletCommandHandler,
				"walletCommandHandler must not be null");
		this.mockBankGateway = Objects.requireNonNull(mockBankGateway, "mockBankGateway must not be null");
	}

	public DepositStatus deposit(UUID depositId, UUID walletId, Money amount, String bankReference) {
		Objects.requireNonNull(depositId, "depositId must not be null");
		Objects.requireNonNull(walletId, "walletId must not be null");
		Objects.requireNonNull(amount, "amount must not be null");
		Objects.requireNonNull(bankReference, "bankReference must not be null");

		Optional<Deposit> existingOpt = depositRepository.load(depositId);
		Deposit deposit;
		if (existingOpt.isPresent()) {
			deposit = existingOpt.get();
			if (!deposit.walletId().equals(walletId) || !deposit.amount().equals(amount)
					|| !deposit.bankReference().equals(bankReference)) {
				throw new ConflictingTransactionException(depositId,
						"Deposit " + depositId + " already exists with different parameters");
			}
			if (deposit.status() == DepositStatus.CONFIRMED) {
				walletCommandHandler.credit(walletId, depositId, amount, BANK_SETTLEMENT_ACCOUNT,
						AccountType.SETTLEMENT);
				return DepositStatus.CONFIRMED;
			}
			if (deposit.status() == DepositStatus.FAILED) {
				return DepositStatus.FAILED;
			}
		} else {
			walletRepository.load(walletId).orElseThrow(() -> new WalletNotFoundException(walletId));
			deposit = Deposit.request(depositId, walletId, amount, bankReference);
			depositRepository.save(deposit);
		}

		DepositVerificationResult verification;
		try {
			verification = mockBankGateway.verifyDeposit(depositId, amount, bankReference);
		} catch (RuntimeException ex) {
			verification = DepositVerificationResult.timeout();
		}
		return switch (verification.status()) {
			case SUCCESS -> {
				deposit.confirm();
				depositRepository.save(deposit);
				walletCommandHandler.credit(walletId, depositId, amount, BANK_SETTLEMENT_ACCOUNT,
						AccountType.SETTLEMENT);
				yield DepositStatus.CONFIRMED;
			}
			case REJECTED -> {
				deposit.fail(verification.reason());
				depositRepository.save(deposit);
				yield DepositStatus.FAILED;
			}
			case TIMEOUT -> DepositStatus.PENDING;
		};
	}
}
