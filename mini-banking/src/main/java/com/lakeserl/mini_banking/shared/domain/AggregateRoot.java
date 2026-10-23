package com.lakeserl.mini_banking.shared.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;

/**
 * Base class of event-sourced aggregates. State changes only happen in {@link #when(DomainEvent)},
 * which is used both when a new event is raised and when history is replayed, so replay always
 * rebuilds exactly the state the commands produced. Business rules live in the aggregate's command
 * methods, <b>not</b> in {@code when}.
 */
public abstract class AggregateRoot {

	private long committedVersion;
	private final List<PendingEvent> uncommitted = new ArrayList<>();

	public abstract UUID id();

	protected abstract void when(DomainEvent event);

	/** Version of the last persisted event (0 for a brand-new aggregate). */
	public long committedVersion() {
		return committedVersion;
	}

	/** Version including events not yet persisted. */
	public long version() {
		return committedVersion + uncommitted.size();
	}

	public List<PendingEvent> uncommittedEvents() {
		return Collections.unmodifiableList(uncommitted);
	}

	public void markCommitted() {
		committedVersion += uncommitted.size();
		uncommitted.clear();
	}

	/** Replays already persisted domain events without stored version metadata (used in simple in-memory tests). */
	public void loadFromHistory(Iterable<? extends DomainEvent> history) {
		for (DomainEvent event : history) {
			when(event);
			committedVersion++;
		}
	}

	/**
	 * Replays persisted events from the event store, validating version continuity.
	 *
	 * @throws IllegalStateException if an event has a non-contiguous version.
	 */
	public void loadFromStoredEvents(List<StoredEvent> events) {
		if (events == null || events.isEmpty()) {
			throw new IllegalArgumentException("events must not be null or empty");
		}
		for (StoredEvent stored : events) {
			if (stored == null || stored.event() == null) {
				throw new IllegalArgumentException("stored events must not contain null values");
			}
			long expected = committedVersion + 1;
			if (stored.version() < 1) {
				throw new IllegalStateException("Event version must be positive but was " + stored.version());
			}
			if (stored.version() != expected) {
				throw new IllegalStateException(
						"Non-contiguous event version: expected " + expected + " but got " + stored.version());
			}
			when(stored.event());
			committedVersion = stored.version();
		}
	}

	public void restoreCommittedVersion(long version) {
		this.committedVersion = version;
	}

	protected void raise(UUID eventId, DomainEvent event, UUID correlationId, UUID causationId) {
		when(event);
		uncommitted.add(new PendingEvent(eventId, event, correlationId, causationId));
	}
}
