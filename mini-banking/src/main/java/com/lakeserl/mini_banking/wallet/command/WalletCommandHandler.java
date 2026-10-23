package com.lakeserl.mini_banking.wallet.command;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.DuplicateEventException;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.shared.eventsourcing.OptimisticConcurrencyException;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.domain.events.WalletCredited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletDebited;
import com.lakeserl.mini_banking.wallet.domain.exceptions.ConflictingTransactionException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletAlreadyExistsException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletNotFoundException;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;

/**
 * Command side for wallets.
 * <ul>
 *   <li>Enforces single wallet per owner per currency (01-specification §3.3).</li>
 *   <li>Credit and debit are idempotent per (transactionId, wallet): retrying with the same parameters
 *       returns {@link ApplyResult#ALREADY_APPLIED}, while retrying with conflicting parameters throws
 *       {@link ConflictingTransactionException} (ADR-010).</li>
 *   <li>Retries up to {@value #MAX_RETRIES} times on optimistic concurrency conflicts so that a new,
 *       distinct transaction reads the correct up-to-date balance after the conflict.</li>
 * </ul>
 *
 * <p><b>Scope note:</b> The {@code transactionId} used here provides event-level idempotency only.
 * Full HTTP-layer idempotency with {@code Idempotency-Key} header, request-hash and TTL expiry
 * as described in ADR-010 is deferred to Phase 1 ({@code IdempotencyService} + {@code IdempotencyFilter}).
 */
@Service
public class WalletCommandHandler {

	public enum ApplyResult {
		APPLIED,
		ALREADY_APPLIED
	}

	public static final int MAX_RETRIES = 3;

	private final WalletRepository repository;
	private final EventStore eventStore;

	public WalletCommandHandler(WalletRepository repository, EventStore eventStore) {
		this.repository = Objects.requireNonNull(repository, "repository must not be null");
		this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
	}

	public UUID openWallet(UUID ownerId, Currency currency) {
		Objects.requireNonNull(ownerId, "ownerId must not be null");
		Objects.requireNonNull(currency, "currency must not be null");
		UUID walletId = Wallet.calculateWalletId(ownerId, currency);
		if (repository.load(walletId).isPresent()) {
			throw new WalletAlreadyExistsException(ownerId, currency);
		}
		Wallet wallet = Wallet.open(walletId, ownerId, currency);
		try {
			repository.save(wallet);
			return wallet.id();
		} catch (OptimisticConcurrencyException e) {
			// Another concurrent openWallet with same (ownerId, currency) won the race.
			throw new WalletAlreadyExistsException(ownerId, currency);
		}
	}

	public ApplyResult credit(UUID walletId, UUID transactionId, Money amount, String counterpartAccountId,
			AccountType counterpartType) {
		Objects.requireNonNull(walletId, "walletId must not be null");
		Objects.requireNonNull(transactionId, "transactionId must not be null");
		Objects.requireNonNull(amount, "amount must not be null");
		Objects.requireNonNull(counterpartAccountId, "counterpartAccountId must not be null");
		Objects.requireNonNull(counterpartType, "counterpartType must not be null");
		DomainEvent expectedEvent = new WalletCredited(walletId, amount, transactionId, counterpartAccountId,
				counterpartType);
		return apply(walletId, Wallet.creditEventId(transactionId, walletId), expectedEvent,
				wallet -> wallet.credit(transactionId, amount, counterpartAccountId, counterpartType));
	}

	public ApplyResult debit(UUID walletId, UUID transactionId, Money amount, String counterpartAccountId,
			AccountType counterpartType) {
		Objects.requireNonNull(walletId, "walletId must not be null");
		Objects.requireNonNull(transactionId, "transactionId must not be null");
		Objects.requireNonNull(amount, "amount must not be null");
		Objects.requireNonNull(counterpartAccountId, "counterpartAccountId must not be null");
		Objects.requireNonNull(counterpartType, "counterpartType must not be null");
		DomainEvent expectedEvent = new WalletDebited(walletId, amount, transactionId, counterpartAccountId,
				counterpartType);
		return apply(walletId, Wallet.debitEventId(transactionId, walletId), expectedEvent,
				wallet -> wallet.debit(transactionId, amount, counterpartAccountId, counterpartType));
	}

	private ApplyResult apply(UUID walletId, UUID eventId, DomainEvent expectedEvent, Consumer<Wallet> action) {
		for (int retryCount = 0;; retryCount++) {
			Optional<StoredEvent> existing = eventStore.findEvent(eventId);
			if (existing.isPresent()) {
				verifySameEvent(existing.get(), expectedEvent);
				return ApplyResult.ALREADY_APPLIED;
			}
			// Each retry loads a fresh snapshot to read the correct balance after a conflict.
			Wallet wallet = repository.load(walletId).orElseThrow(() -> new WalletNotFoundException(walletId));
			action.accept(wallet);
			try {
				repository.save(wallet);
				return ApplyResult.APPLIED;
			} catch (DuplicateEventException e) {
				// Lost a concurrent race for the same event id: verify the winner's payload.
				StoredEvent stored = eventStore.findEvent(eventId).orElseThrow(() -> e);
				verifySameEvent(stored, expectedEvent);
				return ApplyResult.ALREADY_APPLIED;
			} catch (OptimisticConcurrencyException e) {
				if (retryCount >= MAX_RETRIES) {
					throw e;
				}
				// Loop: reload from fresh state on next iteration.
			}
		}
	}

	private void verifySameEvent(StoredEvent stored, DomainEvent expected) {
		if (!stored.event().equals(expected)) {
			throw new ConflictingTransactionException(stored.correlationId(),
					"Transaction was already recorded with different parameters. Existing: "
							+ stored.event() + ", Requested: " + expected);
		}
	}
}
