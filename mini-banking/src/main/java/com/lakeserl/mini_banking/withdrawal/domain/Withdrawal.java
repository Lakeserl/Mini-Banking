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

/** Event-sourced cash-out lifecycle: PENDING -> CONFIRMED | FAILED. */
public final class Withdrawal extends AggregateRoot {

	private static final UUID EVENT_ID_NAMESPACE = UUID.fromString("9c5d3a7e-1b24-4e68-a731-8f2c5d4e9b12");
	private static final UUID REVERSAL_TX_NAMESPACE = UUID.fromString("2b7a9e4c-5f81-4d32-9c64-1a8e3f7b2c50");

	private UUID withdrawalId;
	private UUID walletId;
	private Money amount;
	private String bankAccount;
	private String bankReference;
	private WithdrawalStatus status;
	private String failureReason;

	private Withdrawal() {
	}

	/** Deterministic transaction id for the compensating credit event when a withdrawal fails. */
	public static UUID reversalTransactionId(UUID withdrawalId) {
		Objects.requireNonNull(withdrawalId, "withdrawalId must not be null");
		return DeterministicIds.v5(REVERSAL_TX_NAMESPACE, withdrawalId + "|reversal");
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
			throw new InvalidAmountException("Withdrawal amount must be positive but was " + amount.amount());
		}
		Withdrawal withdrawal = new Withdrawal();
		withdrawal.raise(eventId(withdrawalId, WithdrawalRequested.class),
				new WithdrawalRequested(withdrawalId, walletId, amount, bankAccount), withdrawalId, null);
		return withdrawal;
	}

	public static Withdrawal fromHistory(Iterable<? extends DomainEvent> history) {
		Objects.requireNonNull(history, "history must not be null");
		Withdrawal withdrawal = new Withdrawal();
		withdrawal.loadFromHistory(history);
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
			Objects.requireNonNull(stored.aggregateId(), "stored aggregateId must not be null");
			if (!"Withdrawal".equals(stored.aggregateType())
					|| !stored.aggregateId().equals(withdrawalIdFrom(stored.event()))) {
				throw new IllegalStateException("Stored withdrawal event does not match its aggregate envelope");
			}
		}
		super.loadFromStoredEvents(events);
	}

	private UUID withdrawalIdFrom(DomainEvent event) {
		return switch (event) {
			case WithdrawalRequested e -> e.withdrawalId();
			case WithdrawalConfirmed e -> e.withdrawalId();
			case WithdrawalFailed e -> e.withdrawalId();
			default -> throw new IllegalArgumentException("Unknown withdrawal event: " + event.getClass());
		};
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
			throw new IllegalStateException("Withdrawal " + withdrawalId + " is already in terminal state " + status);
		}
	}

	private static UUID eventId(UUID withdrawalId, Class<? extends DomainEvent> type) {
		return DeterministicIds.v5(EVENT_ID_NAMESPACE, withdrawalId + "|" + type.getSimpleName());
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

	public String bankReference() {
		return bankReference;
	}

	public WithdrawalStatus status() {
		return status;
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
					throw new IllegalStateException("Withdrawal is already requested: " + withdrawalId);
				}
				Objects.requireNonNull(e.withdrawalId(), "WithdrawalRequested.withdrawalId must not be null");
				Objects.requireNonNull(e.walletId(), "WithdrawalRequested.walletId must not be null");
				Objects.requireNonNull(e.amount(), "WithdrawalRequested.amount must not be null");
				Objects.requireNonNull(e.bankAccount(), "WithdrawalRequested.bankAccount must not be null");
				if (!e.amount().isPositive()) {
					throw new IllegalStateException("WithdrawalRequested amount must be positive");
				}
				withdrawalId = e.withdrawalId();
				walletId = e.walletId();
				amount = e.amount();
				bankAccount = e.bankAccount();
				status = WithdrawalStatus.PENDING;
			}
			case WithdrawalConfirmed e -> {
				requireInitialized(e.withdrawalId());
				requirePending();
				Objects.requireNonNull(e.bankReference(), "WithdrawalConfirmed.bankReference must not be null");
				status = WithdrawalStatus.CONFIRMED;
				bankReference = e.bankReference();
			}
			case WithdrawalFailed e -> {
				requireInitialized(e.withdrawalId());
				requirePending();
				Objects.requireNonNull(e.reason(), "WithdrawalFailed.reason must not be null");
				status = WithdrawalStatus.FAILED;
				failureReason = e.reason();
			}
			default -> throw new IllegalArgumentException("Unknown withdrawal event: " + event.getClass());
		}
	}

	private void requireInitialized(UUID eventWithdrawalId) {
		if (withdrawalId == null) {
			throw new IllegalStateException("History must start with WithdrawalRequested");
		}
		if (!withdrawalId.equals(eventWithdrawalId)) {
			throw new IllegalStateException(
					"Event withdrawalId " + eventWithdrawalId + " does not match withdrawal " + withdrawalId);
		}
	}
}
