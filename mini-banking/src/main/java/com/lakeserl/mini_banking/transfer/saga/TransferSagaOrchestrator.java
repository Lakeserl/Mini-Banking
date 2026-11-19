package com.lakeserl.mini_banking.transfer.saga;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.DuplicateEventException;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.shared.eventsourcing.OptimisticConcurrencyException;
import com.lakeserl.mini_banking.transfer.domain.Transfer;
import com.lakeserl.mini_banking.transfer.domain.TransferStatus;
import com.lakeserl.mini_banking.transfer.domain.TransferType;
import com.lakeserl.mini_banking.transfer.domain.exceptions.InvalidTransferStateException;
import com.lakeserl.mini_banking.transfer.domain.exceptions.TransferNotFoundException;
import com.lakeserl.mini_banking.transfer.domain.exceptions.TransferPolicyViolationException;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferRepository;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferSagaStateStore;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.domain.WalletStatus;
import com.lakeserl.mini_banking.wallet.domain.exceptions.ConflictingTransactionException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InsufficientFundsException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InvalidAmountException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletLockedException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletNotFoundException;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;

/**
 * Coordinates internal wallet-to-wallet transfers and refunds across three event-sourced aggregates
 * ({@link Transfer}, source {@link Wallet}, and destination {@link Wallet}).
 *
 * <ul>
 *   <li>Pairs {@link TransferSagaStateStore} and {@link TransferRepository#save(Transfer)} inside a single
 *       {@link TransactionTemplate} block at initiation and every state transition so {@code events} and
 *       {@code transfer_saga_state} never drift.</li>
 *   <li>Executes {@link WalletCommandHandler} commands outside the {@link Transfer} transaction in their own
 *       per-aggregate transactions (ADR-006).</li>
 *   <li>Retries transient {@link OptimisticConcurrencyException} contention up to
 *       {@code transfer.orchestrator.max-contention-retries} before leaving the saga non-terminal for
 *       scheduled recovery.</li>
 * </ul>
 */
@Service
public class TransferSagaOrchestrator {

	public static final String TRANSFER_CLEARING_ACCOUNT = "TRANSFER_CLEARING";
	public static final long MAX_SINGLE_TRANSFER_VND = 50_000_000L;
	public static final long MAX_DAILY_OUTGOING_VND = 200_000_000L;
	public static final int DEFAULT_MAX_CONTENTION_RETRIES = 12;
	public static final long DEFAULT_CONTENTION_BACKOFF_MIN_MS = 5L;
	public static final long DEFAULT_CONTENTION_BACKOFF_MAX_MS = 25L;

	private final TransferRepository transferRepository;
	private final TransferSagaStateStore sagaStateStore;
	private final WalletRepository walletRepository;
	private final WalletCommandHandler walletCommandHandler;
	private final EventStore eventStore;
	private final TransactionTemplate tx;
	private final int maxContentionRetries;
	private final long backoffMinMs;
	private final long backoffMaxMs;

	public TransferSagaOrchestrator(TransferRepository transferRepository,
			TransferSagaStateStore sagaStateStore,
			WalletRepository walletRepository,
			WalletCommandHandler walletCommandHandler,
			EventStore eventStore,
			TransactionTemplate tx) {
		this(transferRepository, sagaStateStore, walletRepository, walletCommandHandler, eventStore, tx,
				DEFAULT_MAX_CONTENTION_RETRIES, DEFAULT_CONTENTION_BACKOFF_MIN_MS, DEFAULT_CONTENTION_BACKOFF_MAX_MS);
	}

	@Autowired
	public TransferSagaOrchestrator(TransferRepository transferRepository,
			TransferSagaStateStore sagaStateStore,
			WalletRepository walletRepository,
			WalletCommandHandler walletCommandHandler,
			EventStore eventStore,
			TransactionTemplate tx,
			@Value("${transfer.orchestrator.max-contention-retries:12}") int maxContentionRetries,
			@Value("${transfer.orchestrator.contention-backoff-min-ms:5}") long backoffMinMs,
			@Value("${transfer.orchestrator.contention-backoff-max-ms:25}") long backoffMaxMs) {
		this.transferRepository = Objects.requireNonNull(transferRepository, "transferRepository must not be null");
		this.sagaStateStore = Objects.requireNonNull(sagaStateStore, "sagaStateStore must not be null");
		this.walletRepository = Objects.requireNonNull(walletRepository, "walletRepository must not be null");
		this.walletCommandHandler = Objects.requireNonNull(walletCommandHandler,
				"walletCommandHandler must not be null");
		this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
		this.tx = Objects.requireNonNull(tx, "tx must not be null");
		if (maxContentionRetries < 0) {
			throw new IllegalArgumentException(
					"transfer.orchestrator.max-contention-retries must be >= 0, got " + maxContentionRetries);
		}
		if (backoffMinMs < 0) {
			throw new IllegalArgumentException(
					"transfer.orchestrator.contention-backoff-min-ms must be >= 0, got " + backoffMinMs);
		}
		if (backoffMaxMs < backoffMinMs) {
			throw new IllegalArgumentException(
					"transfer.orchestrator.contention-backoff-max-ms must be >= backoffMinMs, got "
							+ backoffMaxMs + " < " + backoffMinMs);
		}
		this.maxContentionRetries = maxContentionRetries;
		this.backoffMinMs = backoffMinMs;
		this.backoffMaxMs = backoffMaxMs;
	}

	public Optional<Transfer> findTransfer(UUID transferId) {
		Objects.requireNonNull(transferId, "transferId must not be null");
		return transferRepository.load(transferId);
	}

	public Transfer transfer(UUID transferId, UUID sourceWalletId, UUID destinationWalletId, Money amount) {
		Objects.requireNonNull(transferId, "transferId must not be null");
		Objects.requireNonNull(sourceWalletId, "sourceWalletId must not be null");
		Objects.requireNonNull(destinationWalletId, "destinationWalletId must not be null");
		Objects.requireNonNull(amount, "amount must not be null");

		if (sourceWalletId.equals(destinationWalletId)) {
			throw new TransferPolicyViolationException(
					"Source and destination wallets must be different: " + sourceWalletId);
		}
		if (amount.currency() != Currency.VND) {
			throw new IllegalArgumentException("Unsupported transfer currency: " + amount.currency());
		}
		if (!amount.isPositive()) {
			throw new InvalidAmountException("Transfer amount must be positive but was " + amount.amount());
		}
		if (amount.amount() > MAX_SINGLE_TRANSFER_VND) {
			throw new TransferPolicyViolationException(
					"Single transfer amount " + amount.amount() + " exceeds maximum " + MAX_SINGLE_TRANSFER_VND
							+ " VND");
		}

		Optional<Transfer> existingOpt = transferRepository.load(transferId);
		if (existingOpt.isPresent()) {
			Transfer existing = existingOpt.get();
			verifyStandardTransferMatch(existing, transferId, sourceWalletId, destinationWalletId, amount);
			if (existing.status().isTerminal()) {
				return existing;
			}
			return advance(existing, 0, Integer.MAX_VALUE);
		}

		requireActiveWallet(sourceWalletId);
		requireActiveWallet(destinationWalletId);

		Transfer transfer = Objects.requireNonNull(tx.execute(status -> {
			boolean inserted = sagaStateStore.insertInitiated(transferId, sourceWalletId, destinationWalletId, amount,
					TransferType.STANDARD, null, MAX_DAILY_OUTGOING_VND);
			if (!inserted) {
				Transfer existing = transferRepository.load(transferId)
						.orElseThrow(() -> new TransferNotFoundException(transferId));
				verifyStandardTransferMatch(existing, transferId, sourceWalletId, destinationWalletId, amount);
				return existing;
			}
			Transfer initiated = Transfer.initiate(transferId, sourceWalletId, destinationWalletId, amount,
					TransferType.STANDARD, null);
			transferRepository.save(initiated);
			return initiated;
		}));

		if (transfer.status().isTerminal()) {
			return transfer;
		}
		return advance(transfer, 0, Integer.MAX_VALUE);
	}

	public Transfer refund(UUID refundTransferId, UUID originalTransferId) {
		Objects.requireNonNull(refundTransferId, "refundTransferId must not be null");
		Objects.requireNonNull(originalTransferId, "originalTransferId must not be null");
		if (refundTransferId.equals(originalTransferId)) {
			throw new IllegalArgumentException("refundTransferId must not equal originalTransferId");
		}

		Optional<Transfer> existingOpt = transferRepository.load(refundTransferId);
		if (existingOpt.isPresent()) {
			Transfer existing = existingOpt.get();
			verifyRefundMatch(existing, refundTransferId, originalTransferId);
			if (existing.status().isTerminal()) {
				return existing;
			}
			return advance(existing, 0, Integer.MAX_VALUE);
		}

		Transfer original = transferRepository.load(originalTransferId)
				.orElseThrow(() -> new TransferNotFoundException(originalTransferId));
		if (original.type() != TransferType.STANDARD) {
			throw new InvalidTransferStateException(
					"Cannot refund non-standard transfer " + originalTransferId + " of type " + original.type());
		}
		if (original.status() != TransferStatus.COMPLETED) {
			throw new InvalidTransferStateException(
					"Cannot refund transfer " + originalTransferId + " in status " + original.status());
		}

		UUID refundSource = original.destinationWalletId();
		UUID refundDest = original.sourceWalletId();
		Money amount = original.amount();

		requireActiveWallet(refundSource);
		requireActiveWallet(refundDest);

		Transfer refundTransfer = Objects.requireNonNull(tx.execute(status -> {
			boolean inserted = sagaStateStore.insertInitiated(refundTransferId, refundSource, refundDest, amount,
					TransferType.REFUND, originalTransferId, MAX_DAILY_OUTGOING_VND);
			if (!inserted) {
				Transfer existing = transferRepository.load(refundTransferId)
						.orElseThrow(() -> new TransferNotFoundException(refundTransferId));
				verifyRefundMatch(existing, refundTransferId, originalTransferId);
				return existing;
			}
			Transfer initiated = Transfer.initiate(refundTransferId, refundSource, refundDest, amount,
					TransferType.REFUND, originalTransferId);
			transferRepository.save(initiated);
			return initiated;
		}));

		if (refundTransfer.status().isTerminal()) {
			return refundTransfer;
		}
		return advance(refundTransfer, 0, Integer.MAX_VALUE);
	}

	public Transfer resume(UUID transferId, int currentRetryCount, int maxRetries) {
		Objects.requireNonNull(transferId, "transferId must not be null");
		Transfer transfer = transferRepository.load(transferId)
				.orElseThrow(() -> new TransferNotFoundException(transferId));
		if (transfer.status().isTerminal()) {
			tx.executeWithoutResult(
					status -> sagaStateStore.updateStatus(transfer.id(), transfer.status(), transfer.failureReason()));
			return transfer;
		}
		return advance(transfer, currentRetryCount, maxRetries);
	}

	private Transfer advance(Transfer transfer, int currentRetryCount, int maxRetries) {
		if (transfer.status() == TransferStatus.INITIATED) {
			if (eventStore.findEvent(Wallet.debitEventId(transfer.id(), transfer.sourceWalletId())).isPresent()) {
				transfer = saveTransition(transfer, TransferStatus.INITIATED, Transfer::markDebitCompleted);
			} else {
				try {
					UUID sourceWalletId = transfer.sourceWalletId();
					UUID txId = transfer.id();
					Money amount = transfer.amount();
					runWithContentionRetry(() -> walletCommandHandler.debit(sourceWalletId, txId, amount,
							TRANSFER_CLEARING_ACCOUNT, AccountType.CLEARING));
					transfer = saveTransition(transfer, TransferStatus.INITIATED, Transfer::markDebitCompleted);
				} catch (InsufficientFundsException | WalletLockedException | WalletNotFoundException ex) {
					return saveTransition(transfer, TransferStatus.INITIATED, t -> t.fail(ex.getMessage()));
				} catch (RuntimeException ex) {
					if (eventStore.findEvent(Wallet.debitEventId(transfer.id(), transfer.sourceWalletId()))
							.isPresent()) {
						transfer = saveTransition(transfer, TransferStatus.INITIATED, Transfer::markDebitCompleted);
					} else if (currentRetryCount >= maxRetries) {
						return saveTransition(transfer, TransferStatus.INITIATED,
								t -> t.fail("Max recovery retries exceeded at debit: " + ex.getMessage()));
					} else {
						return transfer;
					}
				}
			}
		}

		if (transfer.status() == TransferStatus.DEBITED) {
			try {
				UUID destWalletId = transfer.destinationWalletId();
				UUID txId = transfer.id();
				Money amount = transfer.amount();
				runWithContentionRetry(() -> walletCommandHandler.credit(destWalletId, txId, amount,
						TRANSFER_CLEARING_ACCOUNT, AccountType.CLEARING));
				return saveTransition(transfer, TransferStatus.DEBITED, Transfer::markCreditAndComplete);
			} catch (WalletLockedException | WalletNotFoundException ex) {
				transfer = saveTransition(transfer, TransferStatus.DEBITED,
						t -> t.startCompensation(ex.getMessage()));
			} catch (RuntimeException ex) {
				if (eventStore.findEvent(Wallet.creditEventId(transfer.id(), transfer.destinationWalletId()))
						.isPresent()) {
					return saveTransition(transfer, TransferStatus.DEBITED, Transfer::markCreditAndComplete);
				} else if (currentRetryCount >= maxRetries) {
					transfer = saveTransition(transfer, TransferStatus.DEBITED,
							t -> t.startCompensation("Max recovery retries exceeded at credit: " + ex.getMessage()));
				} else {
					return transfer;
				}
			}
		}

		if (transfer.status() == TransferStatus.REVERSING) {
			try {
				UUID sourceWalletId = transfer.sourceWalletId();
				UUID txId = transfer.id();
				Money amount = transfer.amount();
				runWithContentionRetry(() -> walletCommandHandler.creditCompensation(sourceWalletId, txId, amount,
						TRANSFER_CLEARING_ACCOUNT, AccountType.CLEARING));
				return saveTransition(transfer, TransferStatus.REVERSING, Transfer::markCompensated);
			} catch (RuntimeException ex) {
				if (eventStore.findEvent(Wallet.creditEventId(transfer.id(), transfer.sourceWalletId()))
						.isPresent()) {
					return saveTransition(transfer, TransferStatus.REVERSING, Transfer::markCompensated);
				}
				return transfer;
			}
		}

		return transfer;
	}

	private Transfer saveTransition(Transfer current, TransferStatus expectedStatus, Consumer<Transfer> mutator) {
		try {
			return Objects.requireNonNull(tx.execute(status -> {
				Transfer latest = transferRepository.load(current.id())
						.orElseThrow(() -> new TransferNotFoundException(current.id()));
				if (latest.status() != expectedStatus) {
					return latest;
				}
				mutator.accept(latest);
				transferRepository.save(latest);
				sagaStateStore.updateStatus(latest.id(), latest.status(), latest.failureReason());
				return latest;
			}));
		} catch (OptimisticConcurrencyException | DuplicateEventException ex) {
			return transferRepository.load(current.id())
					.orElseThrow(() -> new TransferNotFoundException(current.id()));
		}
	}

	private <T> T runWithContentionRetry(Supplier<T> action) {
		for (int attempt = 0;; attempt++) {
			try {
				return action.get();
			} catch (OptimisticConcurrencyException ex) {
				if (attempt >= maxContentionRetries) {
					throw ex;
				}
				sleepBackoff();
			}
		}
	}

	private void sleepBackoff() {
		if (backoffMaxMs <= 0) {
			return;
		}
		long delayMs = backoffMaxMs == backoffMinMs
				? backoffMinMs
				: ThreadLocalRandom.current().nextLong(backoffMinMs, backoffMaxMs + 1);
		if (delayMs <= 0) {
			return;
		}
		try {
			Thread.sleep(delayMs);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		}
	}

	private void requireActiveWallet(UUID walletId) {
		Wallet wallet = walletRepository.load(walletId)
				.orElseThrow(() -> new WalletNotFoundException(walletId));
		if (wallet.status() == WalletStatus.LOCKED) {
			throw new WalletLockedException("Wallet " + walletId + " is locked");
		}
	}

	private static void verifyStandardTransferMatch(Transfer existing, UUID transferId, UUID sourceWalletId,
			UUID destinationWalletId, Money amount) {
		if (existing.type() != TransferType.STANDARD
				|| !existing.sourceWalletId().equals(sourceWalletId)
				|| !existing.destinationWalletId().equals(destinationWalletId)
				|| !existing.amount().equals(amount)) {
			throw new ConflictingTransactionException(transferId,
					"Transfer " + transferId + " already exists with different parameters");
		}
	}

	private static void verifyRefundMatch(Transfer existing, UUID refundTransferId, UUID originalTransferId) {
		if (existing.type() != TransferType.REFUND || !originalTransferId.equals(existing.originalTransferId())) {
			throw new ConflictingTransactionException(refundTransferId,
					"Refund transfer " + refundTransferId + " already exists with different parameters");
		}
	}
}
