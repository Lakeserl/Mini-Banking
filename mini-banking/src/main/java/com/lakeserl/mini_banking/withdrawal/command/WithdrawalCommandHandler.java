package com.lakeserl.mini_banking.withdrawal.command;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.lakeserl.mini_banking.mockbank.MockBankGateway;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.PayoutResult;
import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.domain.exceptions.ConflictingTransactionException;
import com.lakeserl.mini_banking.withdrawal.domain.Withdrawal;
import com.lakeserl.mini_banking.withdrawal.domain.WithdrawalStatus;
import com.lakeserl.mini_banking.withdrawal.infrastructure.WithdrawalRepository;

/**
 * Cash-out handler implementing "Debit First, Confirm Later" (01-specification §3.9, §6.2):
 * debits the wallet before calling the bank and credits it back on rejection or timeout.
 */
@Service
public class WithdrawalCommandHandler {

	public static final String BANK_SETTLEMENT_ACCOUNT = "BANK_SETTLEMENT";
	private static final Logger log = LoggerFactory.getLogger(WithdrawalCommandHandler.class);

	private final WithdrawalRepository withdrawalRepository;
	private final WalletCommandHandler walletCommandHandler;
	private final MockBankGateway mockBankGateway;
	private final EventStore eventStore;

	public WithdrawalCommandHandler(WithdrawalRepository withdrawalRepository,
			WalletCommandHandler walletCommandHandler, MockBankGateway mockBankGateway, EventStore eventStore) {
		this.withdrawalRepository = Objects.requireNonNull(withdrawalRepository,
				"withdrawalRepository must not be null");
		this.walletCommandHandler = Objects.requireNonNull(walletCommandHandler,
				"walletCommandHandler must not be null");
		this.mockBankGateway = Objects.requireNonNull(mockBankGateway, "mockBankGateway must not be null");
		this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
	}

	public WithdrawalStatus withdraw(UUID withdrawalId, UUID walletId, Money amount, String bankAccount) {
		Objects.requireNonNull(withdrawalId, "withdrawalId must not be null");
		Objects.requireNonNull(walletId, "walletId must not be null");
		Objects.requireNonNull(amount, "amount must not be null");
		Objects.requireNonNull(bankAccount, "bankAccount must not be null");

		Optional<Withdrawal> existingOpt = withdrawalRepository.load(withdrawalId);
		Withdrawal withdrawal;
		if (existingOpt.isPresent()) {
			withdrawal = existingOpt.get();
			if (!withdrawal.walletId().equals(walletId) || !withdrawal.amount().equals(amount)
					|| !withdrawal.bankAccount().equals(Withdrawal.maskAccount(bankAccount))) {
				throw new ConflictingTransactionException(withdrawalId,
						"Withdrawal " + withdrawalId + " already exists with different parameters");
			}
			if (withdrawal.status() == WithdrawalStatus.CONFIRMED) {
				return WithdrawalStatus.CONFIRMED;
			}
			if (withdrawal.status() == WithdrawalStatus.FAILED) {
				walletCommandHandler.creditCompensation(walletId, Withdrawal.reversalTransactionId(withdrawalId),
						amount, BANK_SETTLEMENT_ACCOUNT, AccountType.SETTLEMENT);
				return WithdrawalStatus.FAILED;
			}
		} else {
			if (eventStore.contains(Wallet.creditEventId(Withdrawal.reversalTransactionId(withdrawalId), walletId))) {
				return WithdrawalStatus.FAILED;
			}
			withdrawal = Withdrawal.request(withdrawalId, walletId, amount, bankAccount);
		}

		// Step 1: Debit wallet first (throws InsufficientFundsException / WalletLockedException before saving withdrawal)
		walletCommandHandler.debit(walletId, withdrawalId, amount, BANK_SETTLEMENT_ACCOUNT, AccountType.SETTLEMENT);
		if (existingOpt.isEmpty()) {
			try {
				withdrawalRepository.save(withdrawal);
			} catch (RuntimeException saveEx) {
				compensateIfUnpersisted(withdrawalId, walletId, amount, saveEx);
				throw saveEx;
			}
		}

		// Step 2: Call Mock Bank (treat unexpected gateway exceptions as timeout so wallet is always compensated)
		PayoutResult payout;
		try {
			payout = mockBankGateway.payout(withdrawalId, amount, bankAccount);
		} catch (RuntimeException ex) {
			payout = PayoutResult.timeout();
		}
		return switch (payout.status()) {
			case SUCCESS -> {
				withdrawal.confirm(payout.bankReference());
				withdrawalRepository.save(withdrawal);
				yield WithdrawalStatus.CONFIRMED;
			}
			case REJECTED, TIMEOUT -> {
				withdrawal.fail(payout.reason());
				withdrawalRepository.save(withdrawal);
				walletCommandHandler.creditCompensation(walletId, Withdrawal.reversalTransactionId(withdrawalId),
						amount, BANK_SETTLEMENT_ACCOUNT, AccountType.SETTLEMENT);
				yield WithdrawalStatus.FAILED;
			}
		};
	}

	private void compensateIfUnpersisted(UUID withdrawalId, UUID walletId, Money amount, RuntimeException saveEx) {
		try {
			if (withdrawalRepository.load(withdrawalId).isPresent()) {
				return;
			}
		} catch (RuntimeException loadEx) {
			log.error("Could not verify withdrawal {} persistence after save failure; skipping compensation",
					withdrawalId, loadEx);
			saveEx.addSuppressed(loadEx);
			return;
		}
		try {
			walletCommandHandler.creditCompensation(walletId, Withdrawal.reversalTransactionId(withdrawalId),
					amount, BANK_SETTLEMENT_ACCOUNT, AccountType.SETTLEMENT);
		} catch (RuntimeException compEx) {
			log.error("Failed to compensate wallet {} after withdrawal {} save failure", walletId, withdrawalId, compEx);
			saveEx.addSuppressed(compEx);
		}
	}
}
