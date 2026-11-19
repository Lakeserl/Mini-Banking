package com.lakeserl.mini_banking.deposit.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.lakeserl.mini_banking.deposit.domain.events.DepositConfirmed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositFailed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositRequested;
import com.lakeserl.mini_banking.shared.domain.AggregateRoot;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.DeterministicIds;
import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InvalidAmountException;

/** Event-sourced cash-in lifecycle: PENDING -> CONFIRMED | FAILED. */
public final class Deposit extends AggregateRoot {

	private static final UUID EVENT_ID_NAMESPACE = UUID.fromString("4a6e2d1b-9c31-4f8b-8a05-2d9f7b4c1e31");

	private UUID depositId;
	private UUID walletId;
	private Money amount;
	private String bankReference;
	private DepositStatus status;
	private String failureReason;

	private Deposit() {
	}

	public static Deposit request(UUID depositId, UUID walletId, Money amount, String bankReference) {
		Objects.requireNonNull(depositId, "depositId must not be null");
		Objects.requireNonNull(walletId, "walletId must not be null");
		Objects.requireNonNull(amount, "amount must not be null");
		Objects.requireNonNull(bankReference, "bankReference must not be null");
		if (bankReference.isBlank()) {
			throw new IllegalArgumentException("bankReference must not be blank");
		}
		if (amount.currency() != Currency.VND) {
			throw new IllegalArgumentException("Unsupported deposit currency: " + amount.currency());
		}
		if (!amount.isPositive()) {
			throw new InvalidAmountException("Deposit amount must be positive but was " + amount.amount());
		}
		Deposit deposit = new Deposit();
		deposit.raise(eventId(depositId, DepositRequested.class),
				new DepositRequested(depositId, walletId, amount, bankReference), depositId, null);
		return deposit;
	}

	public static Deposit fromHistory(Iterable<? extends DomainEvent> history) {
		Objects.requireNonNull(history, "history must not be null");
		Deposit deposit = new Deposit();
		deposit.loadFromHistory(history);
		return deposit;
	}

	public static Deposit fromStoredEvents(List<StoredEvent> events) {
		Deposit deposit = new Deposit();
		deposit.loadFromStoredEvents(events);
		return deposit;
	}

	@Override
	public void loadFromStoredEvents(List<StoredEvent> events) {
		Objects.requireNonNull(events, "events must not be null");
		for (StoredEvent stored : events) {
			Objects.requireNonNull(stored, "stored event must not be null");
			Objects.requireNonNull(stored.aggregateId(), "stored aggregateId must not be null");
			if (!"Deposit".equals(stored.aggregateType()) || !stored.aggregateId().equals(depositIdFrom(stored.event()))) {
				throw new IllegalStateException("Stored deposit event does not match its aggregate envelope");
			}
		}
		super.loadFromStoredEvents(events);
	}

	private UUID depositIdFrom(DomainEvent event) {
		return switch (event) {
			case DepositRequested e -> e.depositId();
			case DepositConfirmed e -> e.depositId();
			case DepositFailed e -> e.depositId();
			default -> throw new IllegalArgumentException("Unknown deposit event: " + event.getClass());
		};
	}

	public void confirm() {
		requirePending();
		raise(eventId(depositId, DepositConfirmed.class), new DepositConfirmed(depositId), depositId, null);
	}

	public void fail(String reason) {
		Objects.requireNonNull(reason, "reason must not be null");
		if (reason.isBlank()) {
			throw new IllegalArgumentException("reason must not be blank");
		}
		requirePending();
		raise(eventId(depositId, DepositFailed.class), new DepositFailed(depositId, reason), depositId, null);
	}

	private void requirePending() {
		if (status != DepositStatus.PENDING) {
			throw new IllegalStateException("Deposit " + depositId + " is already in terminal state " + status);
		}
	}

	private static UUID eventId(UUID depositId, Class<? extends DomainEvent> type) {
		return DeterministicIds.v5(EVENT_ID_NAMESPACE, depositId + "|" + type.getSimpleName());
	}

	@Override
	public UUID id() {
		return depositId;
	}

	public UUID walletId() {
		return walletId;
	}

	public Money amount() {
		return amount;
	}

	public String bankReference() {
		return bankReference;
	}

	public DepositStatus status() {
		return status;
	}

	public String failureReason() {
		return failureReason;
	}

	@Override
	protected void when(DomainEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		switch (event) {
			case DepositRequested e -> {
				if (depositId != null) {
					throw new IllegalStateException("Deposit is already requested: " + depositId);
				}
				Objects.requireNonNull(e.depositId(), "DepositRequested.depositId must not be null");
				Objects.requireNonNull(e.walletId(), "DepositRequested.walletId must not be null");
				Objects.requireNonNull(e.amount(), "DepositRequested.amount must not be null");
				Objects.requireNonNull(e.bankReference(), "DepositRequested.bankReference must not be null");
				if (!e.amount().isPositive()) {
					throw new IllegalStateException("DepositRequested amount must be positive");
				}
				depositId = e.depositId();
				walletId = e.walletId();
				amount = e.amount();
				bankReference = e.bankReference();
				status = DepositStatus.PENDING;
			}
			case DepositConfirmed e -> {
				requireInitialized(e.depositId());
				requirePending();
				status = DepositStatus.CONFIRMED;
			}
			case DepositFailed e -> {
				requireInitialized(e.depositId());
				requirePending();
				Objects.requireNonNull(e.reason(), "DepositFailed.reason must not be null");
				status = DepositStatus.FAILED;
				failureReason = e.reason();
			}
			default -> throw new IllegalArgumentException("Unknown deposit event: " + event.getClass());
		}
	}

	private void requireInitialized(UUID eventDepositId) {
		if (depositId == null) {
			throw new IllegalStateException("History must start with DepositRequested");
		}
		if (!depositId.equals(eventDepositId)) {
			throw new IllegalStateException(
					"Event depositId " + eventDepositId + " does not match deposit " + depositId);
		}
	}
}
