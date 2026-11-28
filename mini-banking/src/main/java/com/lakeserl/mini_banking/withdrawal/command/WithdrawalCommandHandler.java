package com.lakeserl.mini_banking.withdrawal.command;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.lakeserl.mini_banking.mockbank.MockBankGateway;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.BankTransaction;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.PayoutResult;
import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.DuplicateEventException;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.shared.eventsourcing.OptimisticConcurrencyException;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.domain.WalletStatus;
import com.lakeserl.mini_banking.wallet.domain.events.WalletCredited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletDebited;
import com.lakeserl.mini_banking.wallet.domain.exceptions.ConflictingTransactionException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InsufficientFundsException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletLockedException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletNotFoundException;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;
import com.lakeserl.mini_banking.withdrawal.domain.Withdrawal;
import com.lakeserl.mini_banking.withdrawal.domain.WithdrawalStatus;
import com.lakeserl.mini_banking.withdrawal.infrastructure.WithdrawalRepository;

/**
 * Cash-out handler implementing persist-first "Debit First, Confirm Later":
 * persists {@code WithdrawalRequested} before debiting the wallet, leaves bank timeouts
 * {@code PENDING} for safe retry/reconciliation, and guards compensation on {@code WalletDebited}.
 */
@Service
public class WithdrawalCommandHandler {

	public static final String BANK_SETTLEMENT_ACCOUNT = "BANK_SETTLEMENT";
	public static final int MAX_RETRIES = 3;

	public enum ReconcileOutcome {
		SETTLED,
		UNRESOLVED,
		MISMATCH
	}

	private static final int LOCK_STRIPES = 256;
	private static final String DEFAULT_REJECTION_REASON = "Bank rejected withdrawal";
	private static final String ABANDONED_PRE_DEBIT_REASON = "Abandoned before wallet debit";
	private static final Logger log = LoggerFactory.getLogger(WithdrawalCommandHandler.class);

	private final WithdrawalRepository withdrawalRepository;
	private final WalletRepository walletRepository;
	private final WalletCommandHandler walletCommandHandler;
	private final MockBankGateway mockBankGateway;
	private final EventStore eventStore;
	private final int maxOptimisticRetries;
	private final String accountHashPepper;
	private final ReentrantLock[] locks = new ReentrantLock[LOCK_STRIPES];

	@Autowired
	public WithdrawalCommandHandler(WithdrawalRepository withdrawalRepository,
			WalletRepository walletRepository,
			WalletCommandHandler walletCommandHandler,
			MockBankGateway mockBankGateway,
			EventStore eventStore,
			@Value("${withdrawal.command.max-optimistic-retries:" + MAX_RETRIES + "}") int maxOptimisticRetries,
			@Value("${withdrawal.account-hash.pepper}") String accountHashPepper) {
		this.withdrawalRepository = Objects.requireNonNull(withdrawalRepository,
				"withdrawalRepository must not be null");
		this.walletRepository = Objects.requireNonNull(walletRepository, "walletRepository must not be null");
		this.walletCommandHandler = Objects.requireNonNull(walletCommandHandler,
				"walletCommandHandler must not be null");
		this.mockBankGateway = Objects.requireNonNull(mockBankGateway, "mockBankGateway must not be null");
		this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
		if (maxOptimisticRetries < 0) {
			throw new IllegalArgumentException(
					"withdrawal.command.max-optimistic-retries must be >= 0, got " + maxOptimisticRetries);
		}
		Withdrawal.validatePepper(accountHashPepper);
		this.maxOptimisticRetries = maxOptimisticRetries;
		this.accountHashPepper = accountHashPepper;
		for (int i = 0; i < LOCK_STRIPES; i++) {
			locks[i] = new ReentrantLock();
		}
	}

	public WithdrawalStatus withdraw(UUID withdrawalId, UUID walletId, Money amount, String bankAccount) {
		Objects.requireNonNull(withdrawalId, "withdrawalId must not be null");
		Objects.requireNonNull(walletId, "walletId must not be null");
		Objects.requireNonNull(amount, "amount must not be null");
		String normalizedAccount = Withdrawal.normalizeBankAccount(bankAccount);

		ReentrantLock lock = lockFor(withdrawalId);
		lock.lock();
		try {
			Optional<Withdrawal> existingOpt = withdrawalRepository.load(withdrawalId);
			if (existingOpt.isPresent()) {
				return continueExisting(existingOpt.get(), walletId, amount, normalizedAccount);
			}

			Optional<StoredEvent> orphanReversal = eventStore.findEvent(
					Wallet.creditEventId(Withdrawal.reversalTransactionId(withdrawalId), walletId));
			if (orphanReversal.isPresent()) {
				validateOrphanReversal(withdrawalId, walletId, amount, orphanReversal.get());
				return WithdrawalStatus.FAILED;
			}

			boolean alreadyDebited = false;
			Optional<StoredEvent> orphanDebit = eventStore.findEvent(Wallet.debitEventId(withdrawalId, walletId));
			if (orphanDebit.isPresent()) {
				validateOrphanDebit(withdrawalId, walletId, amount, orphanDebit.get());
				alreadyDebited = true;
			}

			Wallet wallet = walletRepository.load(walletId).orElseThrow(() -> new WalletNotFoundException(walletId));
			if (!alreadyDebited && wallet.status() != WalletStatus.ACTIVE) {
				throw new WalletLockedException("Wallet " + walletId + " is locked");
			}
			Withdrawal created = Withdrawal.request(withdrawalId, walletId, amount, normalizedAccount,
					accountHashPepper);
			if (!alreadyDebited && wallet.balance().isLessThan(amount)) {
				throw new InsufficientFundsException("Insufficient funds");
			}

			try {
				withdrawalRepository.save(created);
			} catch (OptimisticConcurrencyException | DuplicateEventException ex) {
				Withdrawal existing = withdrawalRepository.load(withdrawalId)
						.orElseThrow(() -> new IllegalStateException(
								"Withdrawal not found after concurrent creation: " + withdrawalId, ex));
				return continueExisting(existing, walletId, amount, normalizedAccount);
			}

			if (!alreadyDebited) {
				debitWalletOrFailPreDebit(withdrawalId, walletId, amount);
			}
			return settleWithBank(withdrawalId, walletId, amount, normalizedAccount);
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Reconciles a stale {@code PENDING} withdrawal without initiating a new bank payout:
	 * marks pre-debit abandonments {@code FAILED} (no compensation) and settles debited withdrawals
	 * from {@link MockBankGateway#findPayout(UUID)}.
	 */
	public ReconcileOutcome reconcilePendingWithdrawal(UUID withdrawalId) {
		Objects.requireNonNull(withdrawalId, "withdrawalId must not be null");

		ReentrantLock lock = lockFor(withdrawalId);
		lock.lock();
		try {
			Optional<Withdrawal> existingOpt = withdrawalRepository.load(withdrawalId);
			if (existingOpt.isEmpty()) {
				return ReconcileOutcome.MISMATCH;
			}
			Withdrawal withdrawal = existingOpt.get();
			if (withdrawal.status() == WithdrawalStatus.CONFIRMED) {
				return ReconcileOutcome.SETTLED;
			}
			if (withdrawal.status() == WithdrawalStatus.FAILED) {
				compensateIfDebited(withdrawal.id(), withdrawal.walletId(), withdrawal.amount());
				return ReconcileOutcome.SETTLED;
			}

			if (!isWalletDebited(withdrawal.id(), withdrawal.walletId())) {
				Withdrawal updated = reloadAndTransition(withdrawal.id(), current -> {
					if (current.status() == WithdrawalStatus.PENDING
							&& !isWalletDebited(current.id(), current.walletId())) {
						current.fail(ABANDONED_PRE_DEBIT_REASON);
					}
				});
				if (updated.status() == WithdrawalStatus.CONFIRMED) {
					return ReconcileOutcome.SETTLED;
				}
				if (updated.status() == WithdrawalStatus.FAILED) {
					compensateIfDebited(updated.id(), updated.walletId(), updated.amount());
					return ReconcileOutcome.SETTLED;
				}
			}

			Optional<BankTransaction> bankTxOpt = mockBankGateway.findPayout(withdrawal.id());
			if (bankTxOpt.isEmpty()) {
				return ReconcileOutcome.UNRESOLVED;
			}
			BankTransaction tx = bankTxOpt.get();
			if (!matchesBasePayoutInvariants(tx, withdrawal.id(), withdrawal.amount())) {
				return ReconcileOutcome.MISMATCH;
			}
			if ("TIMEOUT".equals(tx.status())) {
				return ReconcileOutcome.UNRESOLVED;
			}
			if ("SUCCESS".equals(tx.status())) {
				if (tx.bankReference() == null || tx.bankReference().isBlank()) {
					return ReconcileOutcome.MISMATCH;
				}
				Withdrawal updated = reloadAndTransition(withdrawal.id(), current -> {
					if (current.status() == WithdrawalStatus.PENDING) {
						current.confirm(tx.bankReference());
					}
				});
				return (updated.status() == WithdrawalStatus.CONFIRMED)
						? ReconcileOutcome.SETTLED
						: ReconcileOutcome.MISMATCH;
			}
			if ("REJECTED".equals(tx.status())) {
				String safeReason = sanitizeRejectionReason(tx.reason());
				Withdrawal updated = reloadAndTransition(withdrawal.id(), current -> {
					if (current.status() == WithdrawalStatus.PENDING) {
						current.fail(safeReason);
					}
				});
				if (updated.status() == WithdrawalStatus.CONFIRMED) {
					return ReconcileOutcome.MISMATCH;
				}
				compensateIfDebited(withdrawal.id(), withdrawal.walletId(), withdrawal.amount());
				return ReconcileOutcome.SETTLED;
			}
			return ReconcileOutcome.MISMATCH;
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Ensures a {@code FAILED} withdrawal whose wallet was debited has its compensating credit recorded.
	 *
	 * @return {@code true} if a missing compensation credit was applied, {@code false} otherwise
	 */
	public boolean ensureCompensatedIfFailed(UUID withdrawalId) {
		Objects.requireNonNull(withdrawalId, "withdrawalId must not be null");

		ReentrantLock lock = lockFor(withdrawalId);
		lock.lock();
		try {
			Optional<Withdrawal> existingOpt = withdrawalRepository.load(withdrawalId);
			if (existingOpt.isEmpty() || existingOpt.get().status() != WithdrawalStatus.FAILED) {
				return false;
			}
			Withdrawal withdrawal = existingOpt.get();
			return compensateIfDebited(withdrawal.id(), withdrawal.walletId(), withdrawal.amount());
		} finally {
			lock.unlock();
		}
	}

	private WithdrawalStatus continueExisting(Withdrawal existing, UUID walletId, Money amount,
			String normalizedAccount) {
		requireMatchingParameters(existing, walletId, amount, normalizedAccount);
		if (existing.status() == WithdrawalStatus.CONFIRMED) {
			return WithdrawalStatus.CONFIRMED;
		}
		if (existing.status() == WithdrawalStatus.FAILED) {
			compensateIfDebited(existing.id(), walletId, amount);
			return WithdrawalStatus.FAILED;
		}
		if (!isWalletDebited(existing.id(), walletId)) {
			debitWalletOrFailPreDebit(existing.id(), walletId, amount);
		}
		return settleWithBank(existing.id(), walletId, amount, normalizedAccount);
	}

	private void debitWalletOrFailPreDebit(UUID withdrawalId, UUID walletId, Money amount) {
		try {
			walletCommandHandler.debit(walletId, withdrawalId, amount, BANK_SETTLEMENT_ACCOUNT,
					AccountType.SETTLEMENT);
		} catch (InsufficientFundsException | WalletLockedException | WalletNotFoundException ex) {
			if (!isWalletDebited(withdrawalId, walletId)) {
				String reason = preDebitFailureReason(ex);
				reloadAndTransition(withdrawalId, current -> {
					if (current.status() == WithdrawalStatus.PENDING && !isWalletDebited(withdrawalId, walletId)) {
						current.fail(reason);
					}
				});
			}
			throw ex;
		}
	}

	private WithdrawalStatus settleWithBank(UUID withdrawalId, UUID walletId, Money amount, String rawAccount) {
		Optional<BankTransaction> existingTxOpt;
		try {
			existingTxOpt = mockBankGateway.findPayout(withdrawalId);
		} catch (RuntimeException ex) {
			log.warn("Transient failure checking bank payout for withdrawalId={}; keeping PENDING", withdrawalId, ex);
			return WithdrawalStatus.PENDING;
		}
		if (existingTxOpt.isPresent()) {
			BankTransaction tx = existingTxOpt.get();
			if (!matchesBasePayoutInvariants(tx, withdrawalId, amount)) {
				throw new IllegalStateException(
						"Bank payout record failed invariant checks for withdrawal " + withdrawalId);
			}
			if (!"TIMEOUT".equals(tx.status())) {
				if ("SUCCESS".equals(tx.status())) {
					if (tx.bankReference() == null || tx.bankReference().isBlank()) {
						throw new IllegalStateException(
								"Bank payout SUCCESS record has blank bankReference for withdrawal " + withdrawalId);
					}
					return confirmWithdrawal(withdrawalId, tx.bankReference());
				}
				if ("REJECTED".equals(tx.status())) {
					return failAndCompensateWithdrawal(withdrawalId, walletId, amount,
							sanitizeRejectionReason(tx.reason()));
				}
				throw new IllegalStateException(
						"Unexpected bank payout status for withdrawal " + withdrawalId + ": " + tx.status());
			}
		}

		// Re-check Withdrawal status before initiating payout so a concurrent FAILED/CONFIRMED transition
		// on another instance never sends a payout for an already-terminal withdrawal.
		Withdrawal latest = withdrawalRepository.load(withdrawalId)
				.orElseThrow(() -> new IllegalStateException("Withdrawal not found: " + withdrawalId));
		if (latest.status() == WithdrawalStatus.CONFIRMED) {
			return WithdrawalStatus.CONFIRMED;
		}
		if (latest.status() == WithdrawalStatus.FAILED) {
			compensateIfDebited(withdrawalId, walletId, amount);
			return WithdrawalStatus.FAILED;
		}

		PayoutResult payout;
		try {
			payout = mockBankGateway.payout(withdrawalId, amount, rawAccount);
		} catch (RuntimeException ex) {
			payout = PayoutResult.timeout();
		}
		return switch (payout.status()) {
			case TIMEOUT -> WithdrawalStatus.PENDING;
			case SUCCESS -> {
				if (payout.bankReference() == null || payout.bankReference().isBlank()) {
					throw new IllegalStateException(
							"Bank payout returned SUCCESS with blank bankReference for withdrawal " + withdrawalId);
				}
				yield confirmWithdrawal(withdrawalId, payout.bankReference());
			}
			case REJECTED -> failAndCompensateWithdrawal(withdrawalId, walletId, amount,
					sanitizeRejectionReason(payout.reason()));
		};
	}

	private WithdrawalStatus confirmWithdrawal(UUID withdrawalId, String bankReference) {
		Withdrawal updated = reloadAndTransition(withdrawalId, current -> {
			if (current.status() == WithdrawalStatus.PENDING) {
				current.confirm(bankReference);
			}
		});
		if (updated.status() == WithdrawalStatus.FAILED) {
			log.error("Bank returned SUCCESS for already-FAILED withdrawalId={}", withdrawalId);
			throw new IllegalStateException("Bank returned SUCCESS for already-FAILED withdrawal " + withdrawalId);
		}
		return updated.status();
	}

	private WithdrawalStatus failAndCompensateWithdrawal(UUID withdrawalId, UUID walletId, Money amount,
			String reason) {
		Withdrawal updated = reloadAndTransition(withdrawalId, current -> {
			if (current.status() == WithdrawalStatus.PENDING) {
				current.fail(reason);
			}
		});
		if (updated.status() == WithdrawalStatus.CONFIRMED) {
			return WithdrawalStatus.CONFIRMED;
		}
		compensateIfDebited(withdrawalId, walletId, amount);
		return WithdrawalStatus.FAILED;
	}

	private boolean compensateIfDebited(UUID withdrawalId, UUID walletId, Money amount) {
		if (!isWalletDebited(withdrawalId, walletId)) {
			return false;
		}
		if (isReversalCredited(withdrawalId, walletId)) {
			return false;
		}
		// Never refund if the bank already settled SUCCESS or has an uncertain/mismatched payout record.
		Optional<BankTransaction> bankTxOpt = mockBankGateway.findPayout(withdrawalId);
		if (bankTxOpt.isPresent()) {
			BankTransaction tx = bankTxOpt.get();
			if (!"REJECTED".equals(tx.status()) || !matchesBasePayoutInvariants(tx, withdrawalId, amount)) {
				log.error("Refusing compensation for withdrawalId={} walletId={}: bank payout status={}",
						withdrawalId, walletId, tx.status());
				throw new IllegalStateException(
						"Refusing to compensate withdrawal " + withdrawalId + ": bank payout status is " + tx.status());
			}
		}
		walletCommandHandler.creditCompensation(walletId, Withdrawal.reversalTransactionId(withdrawalId),
				amount, BANK_SETTLEMENT_ACCOUNT, AccountType.SETTLEMENT);
		return true;
	}

	private Withdrawal reloadAndTransition(UUID withdrawalId, Consumer<Withdrawal> transition) {
		for (int retryCount = 0;; retryCount++) {
			Withdrawal current = withdrawalRepository.load(withdrawalId)
					.orElseThrow(() -> new IllegalStateException("Withdrawal not found: " + withdrawalId));
			transition.accept(current);
			if (current.uncommittedEvents().isEmpty()) {
				return current;
			}
			try {
				withdrawalRepository.save(current);
				return current;
			} catch (OptimisticConcurrencyException | DuplicateEventException ex) {
				if (retryCount >= maxOptimisticRetries) {
					throw ex;
				}
			}
		}
	}

	private void requireMatchingParameters(Withdrawal existing, UUID walletId, Money amount,
			String normalizedAccount) {
		boolean accountMatches;
		if (existing.bankAccountHash() != null) {
			String actualHash = Withdrawal.hmacAccount(existing.id(), normalizedAccount, accountHashPepper);
			accountMatches = Withdrawal.constantTimeHashEquals(existing.bankAccountHash(), actualHash);
		} else {
			accountMatches = existing.bankAccount().equals(Withdrawal.maskAccount(normalizedAccount));
		}
		if (!existing.walletId().equals(walletId) || !existing.amount().equals(amount) || !accountMatches) {
			throw new ConflictingTransactionException(existing.id(),
					"Withdrawal " + existing.id() + " already exists with different parameters");
		}
	}

	private static void validateOrphanReversal(UUID withdrawalId, UUID walletId, Money amount, StoredEvent stored) {
		if (!(stored.event() instanceof WalletCredited credited)
				|| !credited.walletId().equals(walletId)
				|| !credited.amount().equals(amount)
				|| !BANK_SETTLEMENT_ACCOUNT.equals(credited.counterpartAccountId())
				|| credited.counterpartAccountType() != AccountType.SETTLEMENT) {
			throw new ConflictingTransactionException(withdrawalId,
					"Withdrawal " + withdrawalId + " already exists with different parameters");
		}
	}

	private static void validateOrphanDebit(UUID withdrawalId, UUID walletId, Money amount, StoredEvent stored) {
		if (!(stored.event() instanceof WalletDebited debited)
				|| !debited.walletId().equals(walletId)
				|| !debited.amount().equals(amount)
				|| !BANK_SETTLEMENT_ACCOUNT.equals(debited.counterpartAccountId())
				|| debited.counterpartAccountType() != AccountType.SETTLEMENT) {
			throw new ConflictingTransactionException(withdrawalId,
					"Withdrawal " + withdrawalId + " already exists with different parameters");
		}
	}

	private static boolean matchesBasePayoutInvariants(BankTransaction tx, UUID withdrawalId, Money amount) {
		return "PAYOUT".equals(tx.type())
				&& withdrawalId.equals(tx.transactionId())
				&& amount.equals(tx.amount());
	}

	private static String sanitizeRejectionReason(String reason) {
		return (reason == null || reason.isBlank()) ? DEFAULT_REJECTION_REASON : reason;
	}

	private static String preDebitFailureReason(RuntimeException ex) {
		if (ex instanceof InsufficientFundsException) {
			return "Insufficient funds";
		}
		if (ex instanceof WalletLockedException) {
			return "Wallet locked";
		}
		return "Wallet not found";
	}

	private boolean isWalletDebited(UUID withdrawalId, UUID walletId) {
		return eventStore.contains(Wallet.debitEventId(withdrawalId, walletId));
	}

	private boolean isReversalCredited(UUID withdrawalId, UUID walletId) {
		return eventStore.contains(Wallet.creditEventId(Withdrawal.reversalTransactionId(withdrawalId), walletId));
	}

	private ReentrantLock lockFor(UUID withdrawalId) {
		return locks[Math.floorMod(withdrawalId.hashCode(), LOCK_STRIPES)];
	}
}
