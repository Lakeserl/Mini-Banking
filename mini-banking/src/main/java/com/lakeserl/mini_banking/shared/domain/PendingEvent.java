package com.lakeserl.mini_banking.shared.domain;

import java.util.UUID;
import java.util.Objects;

/**
 * An event raised by an aggregate but not yet persisted. {@code eventId} is chosen by the aggregate
 * (deterministic for money movements, see {@link DeterministicIds}) so a retry produces the same id.
 */
public record PendingEvent(UUID eventId, DomainEvent event, UUID correlationId, UUID causationId) {

	public PendingEvent {
		Objects.requireNonNull(eventId, "eventId must not be null");
		Objects.requireNonNull(event, "event must not be null");
		Objects.requireNonNull(correlationId, "correlationId must not be null");
	}
}
