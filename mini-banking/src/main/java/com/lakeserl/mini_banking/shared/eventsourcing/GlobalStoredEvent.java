package com.lakeserl.mini_banking.shared.eventsourcing;

import java.util.Objects;

/**
 * Stored event paired with its monotonic {@code global_seq} position in the event store
 * for deterministic batched projection rebuilds.
 */
public record GlobalStoredEvent(long globalSeq, StoredEvent storedEvent) {

	public GlobalStoredEvent {
		if (globalSeq <= 0) {
			throw new IllegalArgumentException("globalSeq must be positive");
		}
		Objects.requireNonNull(storedEvent, "storedEvent must not be null");
	}
}
