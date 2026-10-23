package com.lakeserl.mini_banking.wallet.infrastructure;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import com.lakeserl.mini_banking.shared.eventsourcing.EventSerializer;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.shared.eventsourcing.PostgresSnapshotStore;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.domain.WalletSnapshot;

/** Loads and saves {@link Wallet}s: snapshot (if valid) + events after it; snapshot every N events. */
@Repository
public class WalletRepository {

	private static final Logger log = LoggerFactory.getLogger(WalletRepository.class);

	static final String AGGREGATE_TYPE = "Wallet";
	public static final int DEFAULT_SNAPSHOT_INTERVAL = 100;

	private final EventStore eventStore;
	private final PostgresSnapshotStore snapshotStore;
	private final EventSerializer serializer;
	private final int snapshotInterval;

	@Autowired
	public WalletRepository(EventStore eventStore, PostgresSnapshotStore snapshotStore, EventSerializer serializer) {
		this(eventStore, snapshotStore, serializer, DEFAULT_SNAPSHOT_INTERVAL);
	}

	public WalletRepository(EventStore eventStore, PostgresSnapshotStore snapshotStore, EventSerializer serializer,
			int snapshotInterval) {
		if (snapshotInterval <= 0) {
			throw new IllegalArgumentException("snapshotInterval must be greater than zero");
		}
		this.eventStore = eventStore;
		this.snapshotStore = snapshotStore;
		this.serializer = serializer;
		this.snapshotInterval = snapshotInterval;
	}

	public Optional<Wallet> load(UUID walletId) {
		Objects.requireNonNull(walletId, "walletId must not be null");

		Optional<PostgresSnapshotStore.Snapshot> snapshotOpt = snapshotStore.find(walletId, WalletSnapshot.SCHEMA_VERSION);
		Wallet wallet = null;
		long fromVersion = 0;

		if (snapshotOpt.isPresent()) {
			PostgresSnapshotStore.Snapshot snapshot = snapshotOpt.get();
			boolean snapshotValid = true;

			if (!walletId.equals(snapshot.aggregateId())) {
				log.warn("Snapshot aggregateId mismatch: expected {} but got {}", walletId, snapshot.aggregateId());
				snapshotValid = false;
			} else if (!AGGREGATE_TYPE.equals(snapshot.aggregateType())) {
				log.warn("Snapshot aggregateType mismatch: expected {} but got {}", AGGREGATE_TYPE, snapshot.aggregateType());
				snapshotValid = false;
			} else if (snapshot.version() < 1) {
				log.warn("Snapshot version is invalid: {}", snapshot.version());
				snapshotValid = false;
			} else if (eventStore.latestVersion(walletId) < snapshot.version()) {
				log.warn("Snapshot version {} is ahead of event store. Falling back to full replay.", snapshot.version());
				snapshotValid = false;
			}

			if (snapshotValid) {
				try {
					WalletSnapshot state = serializer.fromJson(snapshot.stateJson(), WalletSnapshot.class);
					if (state == null || !walletId.equals(state.walletId())) {
						log.warn("Snapshot state walletId mismatch: expected {}", walletId);
					} else if (state.balance() < 0) {
						log.warn("Snapshot state has invalid negative balance {} for wallet {}. Falling back to full replay.",
								state.balance(), walletId);
					} else if (state.currency() == null || state.status() == null || state.ownerId() == null) {
						log.warn("Snapshot state has null required fields for wallet {}. Falling back to full replay.", walletId);
					} else {
						wallet = Wallet.fromSnapshot(state, snapshot.version());
						fromVersion = snapshot.version();
					}
				} catch (Exception e) {
					log.warn("Failed to deserialize snapshot for wallet {}. Falling back to full replay.", walletId, e);
				}
			}
		}

		List<StoredEvent> tail = eventStore.load(walletId, fromVersion);

		if (wallet == null) {
			if (tail.isEmpty()) {
				return Optional.empty();
			}
			validateEventStream(walletId, tail, 0);
			wallet = Wallet.fromStoredEvents(tail);
			return Optional.of(wallet);
		}

		if (!tail.isEmpty()) {
			validateEventStream(walletId, tail, fromVersion);
			wallet.loadFromStoredEvents(tail);
		}
		return Optional.of(wallet);
	}

	private void validateEventStream(UUID expectedWalletId, List<StoredEvent> events, long expectedStartVersion) {
		long current = expectedStartVersion;
		for (StoredEvent se : events) {
			if (!expectedWalletId.equals(se.aggregateId())) {
				throw new IllegalStateException(
						"Event aggregateId " + se.aggregateId() + " does not match expected " + expectedWalletId);
			}
			if (!AGGREGATE_TYPE.equals(se.aggregateType())) {
				throw new IllegalStateException(
						"Event aggregateType " + se.aggregateType() + " does not match expected " + AGGREGATE_TYPE);
			}
			if (se.version() != current + 1) {
				throw new IllegalStateException(
						"Non-contiguous event stream: expected version " + (current + 1) + " but got " + se.version());
			}
			current = se.version();
		}
	}

	/** Appends the wallet's new events; throws on version conflict or duplicate event id. */
	public void save(Wallet wallet) {
		if (wallet.uncommittedEvents().isEmpty()) {
			return;
		}
		long before = wallet.committedVersion();
		eventStore.append(wallet.id(), AGGREGATE_TYPE, before, wallet.uncommittedEvents());
		wallet.markCommitted();
		long after = wallet.committedVersion();

		// Snapshot is a best-effort optimization; failures should not invalidate committed events.
		if (before / snapshotInterval != after / snapshotInterval) {
			try {
				snapshotStore.save(wallet.id(), AGGREGATE_TYPE, after, WalletSnapshot.SCHEMA_VERSION,
						serializer.toJson(wallet.toSnapshot()));
			} catch (Exception e) {
				log.warn("Failed to save snapshot for wallet {} at version {}: {}", wallet.id(), after, e.getMessage());
			}
		}
	}
}
