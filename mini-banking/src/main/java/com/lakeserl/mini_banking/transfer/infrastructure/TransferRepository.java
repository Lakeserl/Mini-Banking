package com.lakeserl.mini_banking.transfer.infrastructure;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;
import com.lakeserl.mini_banking.transfer.domain.Transfer;

@Repository
public class TransferRepository {

	static final String AGGREGATE_TYPE = "Transfer";

	private final EventStore eventStore;

	public TransferRepository(EventStore eventStore) {
		this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
	}

	public Optional<Transfer> load(UUID transferId) {
		Objects.requireNonNull(transferId, "transferId must not be null");
		List<StoredEvent> events = eventStore.load(transferId, 0);
		if (events.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(Transfer.fromStoredEvents(events));
	}

	public void save(Transfer transfer) {
		Objects.requireNonNull(transfer, "transfer must not be null");
		if (transfer.uncommittedEvents().isEmpty()) {
			return;
		}
		eventStore.append(transfer.id(), AGGREGATE_TYPE, transfer.committedVersion(), transfer.uncommittedEvents());
		transfer.markCommitted();
	}
}
