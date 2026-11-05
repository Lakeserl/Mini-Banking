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

/**
 * Deposit aggregate managing cash-in lifecycle: {@code PENDING -> CONFIRMED | FAILED}.
 */
public final class Deposit extends AggregateRoot {

	public static final String AGGREGATE_TYPE = "Deposit";
	private static final UUID EVENT_ID_NAMESPACE = UUID.fromString("3a8d2c1b-7f4e-4b9a-8c1d-2e5f6a7b8c9d");

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
			throw new InvalidAmountException("Deposit amount must be greater than zero but was " + amount.amount());
		}
		Deposit deposit = new Deposit();
		deposit.raise(eventId(depositId, DepositRequested.class),
				new DepositRequested(depositId, walletId, amount, bankReference), depositId, null);
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
			if (!AGGREGATE_TYPE.equals(stored.aggregateType())
					|| !Objects.equals(stored.aggregateId(), depositIdFrom(stored.event()))) {
				throw new IllegalStateException("Stored deposit event does not match its aggregate envelope");
			}
		}
		super.loadFromStoredEvents(events);
	}

	public void confirm() {
		requirePending();
		raise(eventId(depositId, DepositConfirmed.class), new DepositConfirmed(depositId, bankReference), depositId,
				null);
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
			throw new IllegalStateException("Deposit " + depositId + " is already in terminal status " + status);
		}
	}

	public static UUID eventId(UUID depositId, Class<? extends DomainEvent> type) {
		return DeterministicIds.v5(EVENT_ID_NAMESPACE, depositId + "|" + type.getSimpleName());
	}

	private static UUID depositIdFrom(DomainEvent event) {
		return switch (event) {
			case DepositRequested e -> e.depositId();
			case DepositConfirmed e -> e.depositId();
			case DepositFailed e -> e.depositId();
			default -> throw new IllegalArgumentException("Unknown deposit event: " + event.getClass());
		};
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
					throw new IllegalStateException("Deposit is already initialized: " + depositId);
				}
				depositId = e.depositId();
				walletId = e.walletId();
				amount = e.amount();
				bankReference = e.bankReference();
				status = DepositStatus.PENDING;
			}
			case DepositConfirmed e -> {
				requireMatchingDeposit(e.depositId());
				if (status != DepositStatus.PENDING) {
					throw new IllegalStateException("DepositConfirmed can only be applied to PENDING deposit");
				}
				status = DepositStatus.CONFIRMED;
			}
			case DepositFailed e -> {
				requireMatchingDeposit(e.depositId());
				if (status != DepositStatus.PENDING) {
					throw new IllegalStateException("DepositFailed can only be applied to PENDING deposit");
				}
				status = DepositStatus.FAILED;
				failureReason = e.reason();
			}
			default -> throw new IllegalArgumentException("Unknown deposit event: " + event.getClass());
		}
	}

	private void requireMatchingDeposit(UUID eventDepositId) {
		if (depositId == null) {
			throw new IllegalStateException("History must start with DepositRequested");
		}
		if (!depositId.equals(eventDepositId)) {
			throw new IllegalStateException(
					"Event depositId " + eventDepositId + " does not match deposit " + depositId);
		}
	}
}
