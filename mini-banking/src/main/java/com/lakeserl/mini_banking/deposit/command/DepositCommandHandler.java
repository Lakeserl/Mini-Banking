package com.lakeserl.mini_banking.deposit.command;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.lakeserl.mini_banking.deposit.domain.Deposit;
import com.lakeserl.mini_banking.deposit.domain.DepositStatus;
import com.lakeserl.mini_banking.deposit.infrastructure.DepositBankReferenceStore;
import com.lakeserl.mini_banking.deposit.infrastructure.DepositRepository;
import com.lakeserl.mini_banking.mockbank.MockBankGateway;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.DepositVerificationResult;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.DuplicateEventException;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.shared.eventsourcing.OptimisticConcurrencyException;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.domain.WalletStatus;
import com.lakeserl.mini_banking.wallet.domain.exceptions.ConflictingTransactionException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletLockedException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletNotFoundException;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;

@Service
public class DepositCommandHandler {

	public static final String BANK_SETTLEMENT_ACCOUNT = "BANK_SETTLEMENT";
	public static final int MAX_RETRIES = 3;

	private static final Logger log = LoggerFactory.getLogger(DepositCommandHandler.class);

	private final DepositRepository depositRepository;
	private final DepositBankReferenceStore bankReferenceStore;
	private final WalletRepository walletRepository;
	private final WalletCommandHandler walletCommandHandler;
	private final MockBankGateway mockBankGateway;
	private final EventStore eventStore;
	private final TransactionTemplate tx;
	private final int maxOptimisticRetries;

	@Autowired
	public DepositCommandHandler(DepositRepository depositRepository,
			DepositBankReferenceStore bankReferenceStore,
			WalletRepository walletRepository,
			WalletCommandHandler walletCommandHandler,
			MockBankGateway mockBankGateway,
			EventStore eventStore,
			TransactionTemplate tx,
			@Value("${deposit.command.max-optimistic-retries:" + MAX_RETRIES + "}") int maxOptimisticRetries) {
		this.depositRepository = Objects.requireNonNull(depositRepository, "depositRepository must not be null");
		this.bankReferenceStore = Objects.requireNonNull(bankReferenceStore, "bankReferenceStore must not be null");
		this.walletRepository = Objects.requireNonNull(walletRepository, "walletRepository must not be null");
		this.walletCommandHandler = Objects.requireNonNull(walletCommandHandler,
				"walletCommandHandler must not be null");
		this.mockBankGateway = Objects.requireNonNull(mockBankGateway, "mockBankGateway must not be null");
		this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
		this.tx = Objects.requireNonNull(tx, "tx must not be null");
		if (maxOptimisticRetries < 0) {
			throw new IllegalArgumentException(
					"deposit.command.max-optimistic-retries must be >= 0, got " + maxOptimisticRetries);
		}
		this.maxOptimisticRetries = maxOptimisticRetries;
	}

	public DepositStatus deposit(UUID depositId, UUID walletId, Money amount, String bankReference) {
		Objects.requireNonNull(depositId, "depositId must not be null");
		Objects.requireNonNull(walletId, "walletId must not be null");
		Objects.requireNonNull(amount, "amount must not be null");
		Objects.requireNonNull(bankReference, "bankReference must not be null");

		Optional<Deposit> existingOpt = depositRepository.load(depositId);
		if (existingOpt.isPresent()) {
			return continueExisting(existingOpt.get(), walletId, amount, bankReference);
		}

		Wallet wallet = walletRepository.load(walletId).orElseThrow(() -> new WalletNotFoundException(walletId));
		if (wallet.status() != WalletStatus.ACTIVE) {
			throw new WalletLockedException("Wallet " + walletId + " is locked");
		}

		Deposit created = Deposit.request(depositId, walletId, amount, bankReference);

		// Catch concurrency races outside TransactionTemplate so a rolled-back transaction is never reused.
		boolean claimed;
		try {
			claimed = Boolean.TRUE.equals(tx.execute(status -> {
				if (!bankReferenceStore.tryClaim(depositId, bankReference, walletId, amount)) {
					return false;
				}
				depositRepository.save(created);
				return true;
			}));
		} catch (OptimisticConcurrencyException | DuplicateEventException ex) {
			claimed = false;
		}

		if (!claimed) {
			Deposit existing = depositRepository.load(depositId)
					.orElseThrow(() -> new IllegalStateException("Deposit not found after concurrent claim: " + depositId));
			return continueExisting(existing, walletId, amount, bankReference);
		}

		return verifyAndSettle(depositId, walletId, amount, bankReference);
	}

	/**
	 * Settles a pending deposit that already has durable verification proof ({@code DepositBankVerified}
	 * or a pre-V9 {@code WalletCredited} event) without querying the external bank gateway.
	 */
	public boolean settleIfAlreadyVerified(UUID depositId, UUID walletId, Money amount, String bankReference) {
		Objects.requireNonNull(depositId, "depositId must not be null");
		Objects.requireNonNull(walletId, "walletId must not be null");
		Objects.requireNonNull(amount, "amount must not be null");
		Objects.requireNonNull(bankReference, "bankReference must not be null");

		Optional<Deposit> existingOpt = depositRepository.load(depositId);
		if (existingOpt.isEmpty()) {
			return false;
		}
		Deposit existing = existingOpt.get();
		requireMatchingParameters(existing, walletId, amount, bankReference);
		if (existing.status() == DepositStatus.CONFIRMED) {
			return true;
		}
		if (existing.status() == DepositStatus.FAILED) {
			return false;
		}
		boolean alreadyCredited = isWalletAlreadyCredited(depositId, walletId);
		if (existing.isBankVerified() || alreadyCredited) {
			settleVerifiedDeposit(depositId, walletId, amount, !existing.isBankVerified());
			return true;
		}
		return false;
	}

	private DepositStatus continueExisting(Deposit existing, UUID walletId, Money amount, String bankReference) {
		requireMatchingParameters(existing, walletId, amount, bankReference);
		if (existing.status() == DepositStatus.CONFIRMED) {
			return DepositStatus.CONFIRMED;
		}
		if (existing.status() == DepositStatus.FAILED) {
			return DepositStatus.FAILED;
		}
		boolean alreadyCredited = isWalletAlreadyCredited(existing.id(), walletId);
		if (existing.isBankVerified() || alreadyCredited) {
			return settleVerifiedDeposit(existing.id(), walletId, amount, !existing.isBankVerified());
		}
		return verifyAndSettle(existing.id(), walletId, amount, bankReference);
	}

	private DepositStatus verifyAndSettle(UUID depositId, UUID walletId, Money amount, String bankReference) {
		DepositVerificationResult verification;
		try {
			verification = mockBankGateway.verifyDeposit(depositId, amount, bankReference);
		} catch (RuntimeException ex) {
			verification = DepositVerificationResult.timeout();
		}
		return switch (verification.status()) {
			case TIMEOUT -> DepositStatus.PENDING;
			case REJECTED -> {
				if (isWalletAlreadyCredited(depositId, walletId)) {
					yield settleVerifiedDeposit(depositId, walletId, amount, true);
				}
				String safeReason = (verification.reason() == null || verification.reason().isBlank())
						? "Bank rejected deposit"
						: verification.reason();
				Deposit afterReject = reloadAndTransition(depositId, d -> {
					if (d.status() == DepositStatus.PENDING && !d.isBankVerified()) {
						d.fail(safeReason);
					}
				});
				if (afterReject.status() == DepositStatus.CONFIRMED) {
					yield DepositStatus.CONFIRMED;
				}
				if (afterReject.isBankVerified()) {
					yield settleVerifiedDeposit(depositId, walletId, amount, false);
				}
				yield DepositStatus.FAILED;
			}
			case SUCCESS -> {
				Deposit afterVerify = reloadAndTransition(depositId, d -> {
					if (d.status() == DepositStatus.PENDING) {
						d.markBankVerified();
					}
				});
				if (afterVerify.status() == DepositStatus.CONFIRMED) {
					yield DepositStatus.CONFIRMED;
				}
				if (afterVerify.status() == DepositStatus.FAILED) {
					log.error("Bank returned SUCCESS for already-FAILED depositId={}, walletId={}, bankReference={}",
							depositId, walletId, bankReference);
					throw new IllegalStateException("Bank returned SUCCESS for already-FAILED deposit " + depositId);
				}
				yield settleVerifiedDeposit(depositId, walletId, amount, false);
			}
		};
	}

	private DepositStatus settleVerifiedDeposit(UUID depositId, UUID walletId, Money amount,
			boolean needsBankVerifiedEvent) {
		if (needsBankVerifiedEvent) {
			Deposit verified = reloadAndTransition(depositId, d -> {
				if (d.status() == DepositStatus.PENDING && !d.isBankVerified()) {
					d.markBankVerified();
				}
			});
			if (verified.status() == DepositStatus.CONFIRMED) {
				return DepositStatus.CONFIRMED;
			}
			if (verified.status() == DepositStatus.FAILED) {
				throw new IllegalStateException("Cannot settle already-FAILED deposit " + depositId);
			}
		}
		walletCommandHandler.creditSettlement(walletId, depositId, amount, BANK_SETTLEMENT_ACCOUNT);
		reloadAndTransition(depositId, d -> {
			if (d.status() == DepositStatus.PENDING) {
				d.confirm();
			}
		});
		return DepositStatus.CONFIRMED;
	}

	private Deposit reloadAndTransition(UUID depositId, Consumer<Deposit> transition) {
		for (int retryCount = 0;; retryCount++) {
			Deposit current = depositRepository.load(depositId)
					.orElseThrow(() -> new IllegalStateException("Deposit not found: " + depositId));
			transition.accept(current);
			if (current.uncommittedEvents().isEmpty()) {
				return current;
			}
			try {
				depositRepository.save(current);
				return current;
			} catch (OptimisticConcurrencyException | DuplicateEventException ex) {
				if (retryCount >= maxOptimisticRetries) {
					throw ex;
				}
			}
		}
	}

	private boolean isWalletAlreadyCredited(UUID depositId, UUID walletId) {
		return eventStore.findEvent(Wallet.creditEventId(depositId, walletId)).isPresent();
	}

	private static void requireMatchingParameters(Deposit existing, UUID walletId, Money amount, String bankReference) {
		if (!existing.walletId().equals(walletId) || !existing.amount().equals(amount)
				|| !existing.bankReference().equals(bankReference)) {
			throw new ConflictingTransactionException(existing.id(),
					"Deposit " + existing.id() + " already exists with different parameters");
		}
	}
}
