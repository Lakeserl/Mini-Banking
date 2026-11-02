package com.lakeserl.mini_banking.shared.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
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

	/** Replays already persisted domain events; stream continuity is validated at the repository boundary. */
	public void loadFromHistory(Iterable<? extends DomainEvent> history) {
		Objects.requireNonNull(history, "history must not be null");
		for (DomainEvent event : history) {
			Objects.requireNonNull(event, "history event must not be null");
			when(event);
			committedVersion++;
		}
	}

	/** Replays persisted events while preserving and validating their stored versions. */
	public void loadFromStoredEvents(List<StoredEvent> events) {
		Objects.requireNonNull(events, "events must not be null");
		if (events.isEmpty()) {
			throw new IllegalArgumentException("events must not be empty");
		}

		long expectedVersion = committedVersion + 1;
		UUID expectedAggregateId = id();
		for (StoredEvent stored : events) {
			Objects.requireNonNull(stored, "stored event must not be null");
			Objects.requireNonNull(stored.eventId(), "stored event id must not be null");
			Objects.requireNonNull(stored.aggregateId(), "stored aggregate id must not be null");
			Objects.requireNonNull(stored.aggregateType(), "stored aggregate type must not be null");
			Objects.requireNonNull(stored.eventType(), "stored event type must not be null");
			Objects.requireNonNull(stored.event(), "stored event payload must not be null");
			if (stored.schemaVersion() < 1 || !stored.eventType().equals(stored.event().eventType())) {
				throw new IllegalStateException("Stored event metadata does not match its payload");
			}
			if (stored.version() != expectedVersion) {
				throw new IllegalStateException(
						"Non-contiguous event version: expected " + expectedVersion + " but got " + stored.version());
			}
			if (expectedAggregateId != null && !expectedAggregateId.equals(stored.aggregateId())) {
				throw new IllegalStateException("Stored event belongs to another aggregate: " + stored.aggregateId());
			}
			if (expectedAggregateId == null) {
				expectedAggregateId = stored.aggregateId();
			}
			expectedVersion++;
		}

		for (StoredEvent stored : events) {
			when(stored.event());
			committedVersion = stored.version();
		}
	}

	protected void restoreCommittedVersion(long version) {
		this.committedVersion = version;
	}

	protected void raise(UUID eventId, DomainEvent event, UUID correlationId, UUID causationId) {
		when(event);
		uncommitted.add(new PendingEvent(eventId, event, correlationId, causationId));
	}
}
