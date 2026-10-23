package com.lakeserl.mini_banking.shared.eventsourcing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.PendingEvent;

public interface EventStore {

	/**
	 * Appends events atomically as versions {@code expectedVersion + 1, +2, ...}.
	 *
	 * @throws OptimisticConcurrencyException if the aggregate is no longer at {@code expectedVersion}
	 * @throws DuplicateEventException if one of the event ids is already stored
	 */
	void append(UUID aggregateId, String aggregateType, long expectedVersion, List<PendingEvent> events);

	/** Events with version greater than {@code afterVersion}, ordered by version. */
	List<StoredEvent> load(UUID aggregateId, long afterVersion);

	boolean contains(UUID eventId);

	Optional<StoredEvent> findEvent(UUID eventId);

	/** Returns the highest version persisted for the aggregate, or 0 if none exist. */
	long latestVersion(UUID aggregateId);
}
