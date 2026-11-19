package com.lakeserl.mini_banking.transfer.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.AggregateRoot;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.DeterministicIds;
import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;
import com.lakeserl.mini_banking.transfer.domain.events.TransferCompensated;
import com.lakeserl.mini_banking.transfer.domain.events.TransferCompensationStarted;
import com.lakeserl.mini_banking.transfer.domain.events.TransferCompleted;
import com.lakeserl.mini_banking.transfer.domain.events.TransferCreditCompleted;
import com.lakeserl.mini_banking.transfer.domain.events.TransferDebitCompleted;
import com.lakeserl.mini_banking.transfer.domain.events.TransferFailed;
import com.lakeserl.mini_banking.transfer.domain.events.TransferInitiated;
import com.lakeserl.mini_banking.transfer.domain.exceptions.InvalidTransferStateException;
import com.lakeserl.mini_banking.transfer.domain.exceptions.TransferPolicyViolationException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InvalidAmountException;

/**
 * Event-sourced internal wallet-to-wallet transfer saga aggregate:
 * INITIATED -> DEBITED -> COMPLETED | (REVERSING -> REVERSED), or INITIATED -> FAILED.
 */
public final class Transfer extends AggregateRoot {

	private static final UUID EVENT_ID_NAMESPACE = UUID.fromString("7d3f8c2a-6e41-4b95-9c12-3a8e5f1d7b40");

	private UUID transferId;
	private UUID sourceWalletId;
	private UUID destinationWalletId;
	private Money amount;
	private TransferType type;
	private UUID originalTransferId;
	private TransferStatus status;
	private boolean creditCompleted;
	private String failureReason;

	private Transfer() {
	}

	public static Transfer initiate(UUID transferId, UUID sourceWalletId, UUID destinationWalletId, Money amount,
			TransferType type, UUID originalTransferId) {
		Objects.requireNonNull(transferId, "transferId must not be null");
		Objects.requireNonNull(sourceWalletId, "sourceWalletId must not be null");
		Objects.requireNonNull(destinationWalletId, "destinationWalletId must not be null");
		Objects.requireNonNull(amount, "amount must not be null");
		Objects.requireNonNull(type, "type must not be null");
		if (amount.currency() != Currency.VND) {
			throw new IllegalArgumentException("Unsupported transfer currency: " + amount.currency());
		}
		if (!amount.isPositive()) {
			throw new InvalidAmountException("Transfer amount must be positive but was " + amount.amount());
		}
		if (sourceWalletId.equals(destinationWalletId)) {
			throw new TransferPolicyViolationException(
					"Source and destination wallets must be different: " + sourceWalletId);
		}
		if (type == TransferType.REFUND && originalTransferId == null) {
			throw new IllegalArgumentException("Refund transfer requires originalTransferId");
		}
		if (type == TransferType.STANDARD && originalTransferId != null) {
			throw new IllegalArgumentException("Standard transfer must not have originalTransferId");
		}
		Transfer transfer = new Transfer();
		transfer.raise(eventId(transferId, TransferInitiated.class),
				new TransferInitiated(transferId, sourceWalletId, destinationWalletId, amount, type,
						originalTransferId),
				transferId, null);
		return transfer;
	}

	public static Transfer fromHistory(Iterable<? extends DomainEvent> history) {
		Objects.requireNonNull(history, "history must not be null");
		Transfer transfer = new Transfer();
		transfer.loadFromHistory(history);
		return transfer;
	}

	public static Transfer fromStoredEvents(List<StoredEvent> events) {
		Transfer transfer = new Transfer();
		transfer.loadFromStoredEvents(events);
		return transfer;
	}

	@Override
	public void loadFromStoredEvents(List<StoredEvent> events) {
		Objects.requireNonNull(events, "events must not be null");
		for (StoredEvent stored : events) {
			Objects.requireNonNull(stored, "stored event must not be null");
			Objects.requireNonNull(stored.aggregateId(), "stored aggregateId must not be null");
			Objects.requireNonNull(stored.event(), "stored event payload must not be null");
			if (!"Transfer".equals(stored.aggregateType())
					|| !stored.aggregateId().equals(transferIdFrom(stored.event()))) {
				throw new IllegalStateException("Stored transfer event does not match its aggregate envelope");
			}
		}
		super.loadFromStoredEvents(events);
	}

	private UUID transferIdFrom(DomainEvent event) {
		return switch (event) {
			case TransferInitiated e -> e.transferId();
			case TransferDebitCompleted e -> e.transferId();
			case TransferCreditCompleted e -> e.transferId();
			case TransferCompleted e -> e.transferId();
			case TransferFailed e -> e.transferId();
			case TransferCompensationStarted e -> e.transferId();
			case TransferCompensated e -> e.transferId();
			default -> throw new IllegalArgumentException("Unknown transfer event: " + event.getClass());
		};
	}

	public void markDebitCompleted() {
		requireStatus(TransferStatus.INITIATED);
		raise(eventId(transferId, TransferDebitCompleted.class), new TransferDebitCompleted(transferId), transferId,
				null);
	}

	public void markCreditAndComplete() {
		requireDebitedNotCredited();
		raise(eventId(transferId, TransferCreditCompleted.class), new TransferCreditCompleted(transferId), transferId,
				null);
		raise(eventId(transferId, TransferCompleted.class), new TransferCompleted(transferId), transferId, null);
	}

	public void fail(String reason) {
		Objects.requireNonNull(reason, "reason must not be null");
		if (reason.isBlank()) {
			throw new IllegalArgumentException("reason must not be blank");
		}
		requireStatus(TransferStatus.INITIATED);
		raise(eventId(transferId, TransferFailed.class), new TransferFailed(transferId, reason), transferId, null);
	}

	public void startCompensation(String reason) {
		Objects.requireNonNull(reason, "reason must not be null");
		if (reason.isBlank()) {
			throw new IllegalArgumentException("reason must not be blank");
		}
		requireDebitedNotCredited();
		raise(eventId(transferId, TransferCompensationStarted.class),
				new TransferCompensationStarted(transferId, reason), transferId, null);
	}

	public void markCompensated() {
		requireStatus(TransferStatus.REVERSING);
		raise(eventId(transferId, TransferCompensated.class), new TransferCompensated(transferId), transferId, null);
	}

	private void requireStatus(TransferStatus expected) {
		if (status != expected) {
			throw new InvalidTransferStateException(
					"Transfer " + transferId + " expected status " + expected + " but was " + status);
		}
	}

	private void requireDebitedNotCredited() {
		if (status != TransferStatus.DEBITED || creditCompleted) {
			throw new InvalidTransferStateException(
					"Transfer " + transferId + " expected status DEBITED before credit but was " + status);
		}
	}

	private static UUID eventId(UUID transferId, Class<? extends DomainEvent> type) {
		return DeterministicIds.v5(EVENT_ID_NAMESPACE, transferId + "|" + type.getSimpleName());
	}

	@Override
	public UUID id() {
		return transferId;
	}

	public UUID sourceWalletId() {
		return sourceWalletId;
	}

	public UUID destinationWalletId() {
		return destinationWalletId;
	}

	public Money amount() {
		return amount;
	}

	public TransferType type() {
		return type;
	}

	public UUID originalTransferId() {
		return originalTransferId;
	}

	public TransferStatus status() {
		return status;
	}

	public String failureReason() {
		return failureReason;
	}

	@Override
	protected void when(DomainEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		switch (event) {
			case TransferInitiated e -> {
				if (transferId != null) {
					throw new IllegalStateException("Transfer is already initiated: " + transferId);
				}
				Objects.requireNonNull(e.transferId(), "TransferInitiated.transferId must not be null");
				Objects.requireNonNull(e.sourceWalletId(), "TransferInitiated.sourceWalletId must not be null");
				Objects.requireNonNull(e.destinationWalletId(),
						"TransferInitiated.destinationWalletId must not be null");
				Objects.requireNonNull(e.amount(), "TransferInitiated.amount must not be null");
				Objects.requireNonNull(e.type(), "TransferInitiated.type must not be null");
				if (e.amount().currency() != Currency.VND || !e.amount().isPositive()) {
					throw new IllegalStateException("TransferInitiated amount violates transfer invariants");
				}
				if (e.sourceWalletId().equals(e.destinationWalletId())) {
					throw new IllegalStateException("TransferInitiated source and destination wallets must differ");
				}
				if ((e.type() == TransferType.REFUND && e.originalTransferId() == null)
						|| (e.type() == TransferType.STANDARD && e.originalTransferId() != null)) {
					throw new IllegalStateException("TransferInitiated type and originalTransferId are inconsistent");
				}
				transferId = e.transferId();
				sourceWalletId = e.sourceWalletId();
				destinationWalletId = e.destinationWalletId();
				amount = e.amount();
				type = e.type();
				originalTransferId = e.originalTransferId();
				status = TransferStatus.INITIATED;
			}
			case TransferDebitCompleted e -> {
				requireInitialized(e.transferId());
				requireStatus(TransferStatus.INITIATED);
				status = TransferStatus.DEBITED;
			}
			case TransferCreditCompleted e -> {
				requireInitialized(e.transferId());
				requireDebitedNotCredited();
				creditCompleted = true;
			}
			case TransferCompleted e -> {
				requireInitialized(e.transferId());
				if (status != TransferStatus.DEBITED || !creditCompleted) {
					throw new InvalidTransferStateException(
							"Transfer " + transferId + " cannot complete from status " + status);
				}
				status = TransferStatus.COMPLETED;
			}
			case TransferFailed e -> {
				requireInitialized(e.transferId());
				requireStatus(TransferStatus.INITIATED);
				Objects.requireNonNull(e.reason(), "TransferFailed.reason must not be null");
				status = TransferStatus.FAILED;
				failureReason = e.reason();
			}
			case TransferCompensationStarted e -> {
				requireInitialized(e.transferId());
				requireDebitedNotCredited();
				Objects.requireNonNull(e.reason(), "TransferCompensationStarted.reason must not be null");
				status = TransferStatus.REVERSING;
				failureReason = e.reason();
			}
			case TransferCompensated e -> {
				requireInitialized(e.transferId());
				requireStatus(TransferStatus.REVERSING);
				status = TransferStatus.REVERSED;
			}
			default -> throw new IllegalArgumentException("Unknown transfer event: " + event.getClass());
		}
	}

	private void requireInitialized(UUID eventTransferId) {
		if (transferId == null) {
			throw new IllegalStateException("History must start with TransferInitiated");
		}
		if (!transferId.equals(eventTransferId)) {
			throw new IllegalStateException(
					"Event transferId " + eventTransferId + " does not match transfer " + transferId);
		}
	}
}
