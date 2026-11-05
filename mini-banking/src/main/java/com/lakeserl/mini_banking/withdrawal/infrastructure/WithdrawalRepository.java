package com.lakeserl.mini_banking.withdrawal.infrastructure;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;
import com.lakeserl.mini_banking.withdrawal.domain.Withdrawal;

@Repository
public class WithdrawalRepository {

	private final EventStore eventStore;

	public WithdrawalRepository(EventStore eventStore) {
		this.eventStore = Objects.requireNonNull(eventStore, "eventStore must not be null");
	}

	public Optional<Withdrawal> load(UUID withdrawalId) {
		Objects.requireNonNull(withdrawalId, "withdrawalId must not be null");
		List<StoredEvent> events = eventStore.load(withdrawalId, 0);
		if (events.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(Withdrawal.fromStoredEvents(events));
	}

	public void save(Withdrawal withdrawal) {
		Objects.requireNonNull(withdrawal, "withdrawal must not be null");
		if (withdrawal.uncommittedEvents().isEmpty()) {
			return;
		}
		eventStore.append(withdrawal.id(), Withdrawal.AGGREGATE_TYPE, withdrawal.committedVersion(),
				withdrawal.uncommittedEvents());
		withdrawal.markCommitted();
	}
}
