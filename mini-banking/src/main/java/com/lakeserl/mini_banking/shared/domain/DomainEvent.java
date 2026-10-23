package com.lakeserl.mini_banking.shared.domain;

/**
 * Marker for domain events (immutable facts, named in past tense). Implementations are records.
 * The envelope data (event id, version, timestamps, correlation) lives in the event store, not here.
 */
public interface DomainEvent {

	/** Name stored in {@code events.event_type}. */
	default String eventType() {
		return getClass().getSimpleName();
	}
}
