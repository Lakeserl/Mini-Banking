package com.lakeserl.mini_banking.deposit.infrastructure;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.lakeserl.mini_banking.deposit.domain.Deposit;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;

@Repository
public class DepositRepository {

	static final String AGGREGATE_TYPE = "Deposit";

	private final EventStore eventStore;

	public DepositRepository(EventStore eventStore) {
		this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
	}

	public Optional<Deposit> load(UUID depositId) {
		Objects.requireNonNull(depositId, "depositId must not be null");
		List<StoredEvent> events = eventStore.load(depositId, 0);
		if (events.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(Deposit.fromStoredEvents(events));
	}

	public void save(Deposit deposit) {
		Objects.requireNonNull(deposit, "deposit must not be null");
		if (deposit.uncommittedEvents().isEmpty()) {
			return;
		}
		eventStore.append(deposit.id(), AGGREGATE_TYPE, deposit.committedVersion(), deposit.uncommittedEvents());
		deposit.markCommitted();
	}
}
