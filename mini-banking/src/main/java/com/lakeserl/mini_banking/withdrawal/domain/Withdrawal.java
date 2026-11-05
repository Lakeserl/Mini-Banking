package com.lakeserl.mini_banking.withdrawal.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.AggregateRoot;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.DeterministicIds;
import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InvalidAmountException;
import com.lakeserl.mini_banking.withdrawal.domain.events.WithdrawalConfirmed;
import com.lakeserl.mini_banking.withdrawal.domain.events.WithdrawalFailed;
import com.lakeserl.mini_banking.withdrawal.domain.events.WithdrawalRequested;

/**
 * Withdrawal aggregate managing cash-out lifecycle: {@code PENDING -> CONFIRMED | FAILED}.
 */
public final class Withdrawal extends AggregateRoot {

	public static final String AGGREGATE_TYPE = "Withdrawal";
	private static final UUID EVENT_ID_NAMESPACE = UUID.fromString("9b7e4f2d-1a5c-4e8b-b3d1-6c9a2e4f8b01");

	private UUID withdrawalId;
	private UUID walletId;
	private Money amount;
	private String bankAccount;
	private WithdrawalStatus status;
	private String bankReference;
	private String failureReason;

	private Withdrawal() {
	}

	public static Withdrawal request(UUID withdrawalId, UUID walletId, Money amount, String bankAccount) {
		Objects.requireNonNull(withdrawalId, "withdrawalId must not be null");
		Objects.requireNonNull(walletId, "walletId must not be null");
		Objects.requireNonNull(amount, "amount must not be null");
		Objects.requireNonNull(bankAccount, "bankAccount must not be null");
		if (bankAccount.isBlank()) {
			throw new IllegalArgumentException("bankAccount must not be blank");
		}
		if (amount.currency() != Currency.VND) {
			throw new IllegalArgumentException("Unsupported withdrawal currency: " + amount.currency());
		}
		if (!amount.isPositive()) {
			throw new InvalidAmountException(
					"Withdrawal amount must be greater than zero but was " + amount.amount());
		}
		Withdrawal withdrawal = new Withdrawal();
		withdrawal.raise(eventId(withdrawalId, WithdrawalRequested.class),
				new WithdrawalRequested(withdrawalId, walletId, amount, bankAccount), withdrawalId, null);
		return withdrawal;
	}

	public static Withdrawal fromStoredEvents(List<StoredEvent> events) {
		Withdrawal withdrawal = new Withdrawal();
		withdrawal.loadFromStoredEvents(events);
		return withdrawal;
	}

	@Override
	public void loadFromStoredEvents(List<StoredEvent> events) {
		Objects.requireNonNull(events, "events must not be null");
		for (StoredEvent stored : events) {
			Objects.requireNonNull(stored, "stored event must not be null");
			if (!AGGREGATE_TYPE.equals(stored.aggregateType())
					|| !Objects.equals(stored.aggregateId(), withdrawalIdFrom(stored.event()))) {
				throw new IllegalStateException("Stored withdrawal event does not match its aggregate envelope");
			}
		}
		super.loadFromStoredEvents(events);
	}

	public void confirm(String bankReference) {
		Objects.requireNonNull(bankReference, "bankReference must not be null");
		if (bankReference.isBlank()) {
			throw new IllegalArgumentException("bankReference must not be blank");
		}
		requirePending();
		raise(eventId(withdrawalId, WithdrawalConfirmed.class), new WithdrawalConfirmed(withdrawalId, bankReference),
				withdrawalId, null);
	}

	public void fail(String reason) {
		Objects.requireNonNull(reason, "reason must not be null");
		if (reason.isBlank()) {
			throw new IllegalArgumentException("reason must not be blank");
		}
		requirePending();
		raise(eventId(withdrawalId, WithdrawalFailed.class), new WithdrawalFailed(withdrawalId, reason), withdrawalId,
				null);
	}

	private void requirePending() {
		if (status != WithdrawalStatus.PENDING) {
			throw new IllegalStateException(
					"Withdrawal " + withdrawalId + " is already in terminal status " + status);
		}
	}

	public static UUID eventId(UUID withdrawalId, Class<? extends DomainEvent> type) {
		return DeterministicIds.v5(EVENT_ID_NAMESPACE, withdrawalId + "|" + type.getSimpleName());
	}

	/** Deterministic transaction id for the compensating credit, so retrying compensation never double-credits. */
	public static UUID reversalTransactionId(UUID withdrawalId) {
		Objects.requireNonNull(withdrawalId, "withdrawalId must not be null");
		return DeterministicIds.v5(EVENT_ID_NAMESPACE, withdrawalId + "|ReversalCredit");
	}

	private static UUID withdrawalIdFrom(DomainEvent event) {
		return switch (event) {
			case WithdrawalRequested e -> e.withdrawalId();
			case WithdrawalConfirmed e -> e.withdrawalId();
			case WithdrawalFailed e -> e.withdrawalId();
			default -> throw new IllegalArgumentException("Unknown withdrawal event: " + event.getClass());
		};
	}

	@Override
	public UUID id() {
		return withdrawalId;
	}

	public UUID walletId() {
		return walletId;
	}

	public Money amount() {
		return amount;
	}

	public String bankAccount() {
		return bankAccount;
	}

	public WithdrawalStatus status() {
		return status;
	}

	public String bankReference() {
		return bankReference;
	}

	public String failureReason() {
		return failureReason;
	}

	@Override
	protected void when(DomainEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		switch (event) {
			case WithdrawalRequested e -> {
				if (withdrawalId != null) {
					throw new IllegalStateException("Withdrawal is already initialized: " + withdrawalId);
				}
				withdrawalId = e.withdrawalId();
				walletId = e.walletId();
				amount = e.amount();
				bankAccount = e.bankAccount();
				status = WithdrawalStatus.PENDING;
			}
			case WithdrawalConfirmed e -> {
				requireMatchingWithdrawal(e.withdrawalId());
				if (status != WithdrawalStatus.PENDING) {
					throw new IllegalStateException("WithdrawalConfirmed can only be applied to PENDING withdrawal");
				}
				status = WithdrawalStatus.CONFIRMED;
				bankReference = e.bankReference();
			}
			case WithdrawalFailed e -> {
				requireMatchingWithdrawal(e.withdrawalId());
				if (status != WithdrawalStatus.PENDING) {
					throw new IllegalStateException("WithdrawalFailed can only be applied to PENDING withdrawal");
				}
				status = WithdrawalStatus.FAILED;
				failureReason = e.reason();
			}
			default -> throw new IllegalArgumentException("Unknown withdrawal event: " + event.getClass());
		}
	}

	private void requireMatchingWithdrawal(UUID eventWithdrawalId) {
		if (withdrawalId == null) {
			throw new IllegalStateException("History must start with WithdrawalRequested");
		}
		if (!withdrawalId.equals(eventWithdrawalId)) {
			throw new IllegalStateException(
					"Event withdrawalId " + eventWithdrawalId + " does not match withdrawal " + withdrawalId);
		}
	}
}
