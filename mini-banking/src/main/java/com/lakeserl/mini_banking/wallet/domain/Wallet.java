package com.lakeserl.mini_banking.wallet.domain;

import java.util.Objects;
import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.AggregateRoot;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.DeterministicIds;
import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;
import com.lakeserl.mini_banking.wallet.domain.events.WalletCredited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletDebited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletLocked;
import com.lakeserl.mini_banking.wallet.domain.events.WalletOpened;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InsufficientFundsException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InvalidAmountException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletLockedException;

/**
 * Wallet aggregate: balance and status. Knows "is there enough money / is the wallet active";
 * transfer limits and other policies belong to the callers.
 *
 * <p>Credits and debits get a <b>deterministic event id</b> derived from (transactionId, walletId, event type).
 * Retrying the same movement after a crash therefore produces the same id and the event store's primary key
 * rejects the duplicate. Consequence: a transaction may credit and debit a given wallet at most once each.
 */
public final class Wallet extends AggregateRoot {

	private static final UUID EVENT_ID_NAMESPACE = UUID.fromString("6f1f1f0e-3b8c-4d5a-9a52-7f0f3a1c2b10");
	private static final UUID WALLET_NAMESPACE = UUID.fromString("e7c2e391-4d1a-4c92-959c-8519782787c8");

	public static UUID calculateWalletId(UUID ownerId, Currency currency) {
		Objects.requireNonNull(ownerId, "ownerId must not be null");
		Objects.requireNonNull(currency, "currency must not be null");
		return DeterministicIds.v5(WALLET_NAMESPACE, ownerId + "|" + currency);
	}

	private UUID walletId;
	private UUID ownerId;
	private Currency currency;
	private Money balance;
	private WalletStatus status;

	private Wallet() {
	}

	public static Wallet open(UUID walletId, UUID ownerId, Currency currency) {
		Objects.requireNonNull(walletId, "walletId must not be null");
		Objects.requireNonNull(ownerId, "ownerId must not be null");
		Objects.requireNonNull(currency, "currency must not be null");
		if (currency != Currency.VND) {
			throw new IllegalArgumentException("Unsupported wallet currency: " + currency);
		}
		Wallet wallet = new Wallet();
		wallet.raise(UUID.randomUUID(), new WalletOpened(walletId, ownerId, currency), walletId, null);
		return wallet;
	}

	public static Wallet fromHistory(Iterable<? extends DomainEvent> history) {
		Objects.requireNonNull(history, "history must not be null");
		Wallet wallet = new Wallet();
		wallet.loadFromHistory(history);
		return wallet;
	}

	public static Wallet fromStoredEvents(java.util.List<StoredEvent> events) {
		Objects.requireNonNull(events, "events must not be null");
		Wallet wallet = new Wallet();
		wallet.loadFromStoredEvents(events);
		return wallet;
	}

	public static Wallet fromSnapshot(WalletSnapshot snapshot, long version) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		Wallet wallet = new Wallet();
		wallet.walletId = snapshot.walletId();
		wallet.ownerId = snapshot.ownerId();
		wallet.currency = snapshot.currency();
		wallet.balance = new Money(snapshot.balance(), snapshot.currency());
		wallet.status = snapshot.status();
		wallet.restoreCommittedVersion(version);
		return wallet;
	}

	public static UUID creditEventId(UUID transactionId, UUID walletId) {
		Objects.requireNonNull(transactionId, "transactionId must not be null");
		Objects.requireNonNull(walletId, "walletId must not be null");
		return eventId(transactionId, walletId, WalletCredited.class);
	}

	public static UUID debitEventId(UUID transactionId, UUID walletId) {
		Objects.requireNonNull(transactionId, "transactionId must not be null");
		Objects.requireNonNull(walletId, "walletId must not be null");
		return eventId(transactionId, walletId, WalletDebited.class);
	}

	private static UUID eventId(UUID transactionId, UUID walletId, Class<? extends DomainEvent> type) {
		return DeterministicIds.v5(EVENT_ID_NAMESPACE,
				transactionId + "|" + walletId + "|" + type.getSimpleName());
	}

	public void credit(UUID transactionId, Money amount, String counterpartAccountId, AccountType counterpartType) {
		Objects.requireNonNull(transactionId, "transactionId must not be null");
		Objects.requireNonNull(amount, "amount must not be null");
		Objects.requireNonNull(counterpartAccountId, "counterpartAccountId must not be null");
		Objects.requireNonNull(counterpartType, "counterpartType must not be null");
		requireMovable(amount);
		raise(creditEventId(transactionId, walletId),
				new WalletCredited(walletId, amount, transactionId, counterpartAccountId, counterpartType),
				transactionId, null);
	}

	public void debit(UUID transactionId, Money amount, String counterpartAccountId, AccountType counterpartType) {
		Objects.requireNonNull(transactionId, "transactionId must not be null");
		Objects.requireNonNull(amount, "amount must not be null");
		Objects.requireNonNull(counterpartAccountId, "counterpartAccountId must not be null");
		Objects.requireNonNull(counterpartType, "counterpartType must not be null");
		requireMovable(amount);
		if (balance.isLessThan(amount)) {
			throw new InsufficientFundsException(
					"Wallet " + walletId + " has " + balance.amount() + " but " + amount.amount() + " is required");
		}
		raise(debitEventId(transactionId, walletId),
				new WalletDebited(walletId, amount, transactionId, counterpartAccountId, counterpartType),
				transactionId, null);
	}

	/** Locking an already locked wallet is a no-op. */
	public void lock(String reason) {
		Objects.requireNonNull(reason, "reason must not be null");
		if (status == WalletStatus.LOCKED) {
			return;
		}
		raise(UUID.randomUUID(), new WalletLocked(walletId, reason), walletId, null);
	}

	private void requireMovable(Money amount) {
		if (status == WalletStatus.LOCKED) {
			throw new WalletLockedException("Wallet " + walletId + " is locked");
		}
		balance.compareTo(amount); // throws CurrencyMismatchException
		if (!amount.isPositive()) {
			throw new InvalidAmountException("Amount must be greater than zero but was " + amount.amount());
		}
	}

	public WalletSnapshot toSnapshot() {
		return new WalletSnapshot(walletId, ownerId, currency, balance.amount(), status);
	}

	@Override
	public UUID id() {
		return walletId;
	}

	public UUID ownerId() {
		return ownerId;
	}

	public Currency currency() {
		return currency;
	}

	public Money balance() {
		return balance;
	}

	public WalletStatus status() {
		return status;
	}

	@Override
	protected void when(DomainEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		switch (event) {
			case WalletOpened e -> {
				if (walletId != null && !walletId.equals(e.walletId())) {
					throw new IllegalStateException("Event walletId " + e.walletId() + " does not match wallet " + walletId);
				}
				walletId = e.walletId();
				ownerId = e.ownerId();
				currency = e.currency();
				balance = Money.zero(e.currency());
				status = WalletStatus.ACTIVE;
			}
			case WalletCredited e -> {
				if (walletId != null && !walletId.equals(e.walletId())) {
					throw new IllegalStateException("Event walletId " + e.walletId() + " does not match wallet " + walletId);
				}
				balance = balance.plus(e.amount());
			}
			case WalletDebited e -> {
				if (walletId != null && !walletId.equals(e.walletId())) {
					throw new IllegalStateException("Event walletId " + e.walletId() + " does not match wallet " + walletId);
				}
				balance = balance.minus(e.amount());
			}
			case WalletLocked e -> {
				if (walletId != null && !walletId.equals(e.walletId())) {
					throw new IllegalStateException("Event walletId " + e.walletId() + " does not match wallet " + walletId);
				}
				status = WalletStatus.LOCKED;
			}
			default -> throw new IllegalArgumentException("Unknown wallet event: " + event.getClass());
		}
	}
}
