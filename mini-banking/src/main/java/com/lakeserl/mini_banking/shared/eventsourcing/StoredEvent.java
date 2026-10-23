package com.lakeserl.mini_banking.shared.eventsourcing;

import java.time.Instant;
import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.DomainEvent;

/** An event as read back from the store, with its envelope data. */
public record StoredEvent(UUID eventId, UUID aggregateId, String aggregateType, long version, String eventType,
		int schemaVersion, Instant occurredAt, UUID correlationId, UUID causationId, DomainEvent event) {
}
