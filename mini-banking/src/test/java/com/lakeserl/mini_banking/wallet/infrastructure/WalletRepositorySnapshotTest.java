package com.lakeserl.mini_banking.wallet.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.EventSerializer;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.shared.eventsourcing.PostgresSnapshotStore;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.domain.WalletSnapshot;
import com.lakeserl.mini_banking.wallet.domain.events.WalletOpened;

@PostgresDataTest
class WalletRepositorySnapshotTest {

	@Autowired
	EventStore eventStore;

	@Autowired
	PostgresSnapshotStore snapshotStore;

	@Autowired
	EventSerializer serializer;

	@Autowired
	JdbcTemplate jdbc;

	private Wallet walletWithMovements(WalletRepository repository, int credits) {
		Wallet wallet = Wallet.open(UUID.randomUUID(), UUID.randomUUID(), Currency.VND);
		repository.save(wallet);
		for (int i = 0; i < credits; i++) {
			wallet = repository.load(wallet.id()).orElseThrow();
			wallet.credit(UUID.randomUUID(), Money.vnd(10 + i), "BANK_SETTLEMENT", AccountType.SETTLEMENT);
			repository.save(wallet);
		}
		return wallet;
	}

	@Test
	void loadWithSnapshotEqualsFullReplayOf250Events() {
		WalletRepository repository = new WalletRepository(eventStore, snapshotStore, serializer, 100);
		Wallet written = walletWithMovements(repository, 249); // open + 249 credits = 250 events

		Integer snapshotVersion = jdbc.queryForObject("SELECT version FROM snapshots WHERE aggregate_id = ?",
				Integer.class, written.id());
		assertThat(snapshotVersion).isEqualTo(200);

		Wallet viaSnapshot = repository.load(written.id()).orElseThrow();
		Wallet viaFullReplay = Wallet.fromHistory(
				eventStore.load(written.id(), 0).stream().map(StoredEvent::event).toList());

		assertThat(viaSnapshot.committedVersion()).isEqualTo(250).isEqualTo(viaFullReplay.committedVersion());
		assertThat(viaSnapshot.balance()).isEqualTo(viaFullReplay.balance()).isEqualTo(written.balance());
		assertThat(viaSnapshot.toSnapshot()).isEqualTo(viaFullReplay.toSnapshot());
	}

	@Test
	void snapshotWithOutdatedSchemaVersionIsIgnoredAndRebuiltFromEvents() {
		WalletRepository repository = new WalletRepository(eventStore, snapshotStore, serializer, 10);
		Wallet written = walletWithMovements(repository, 14); // snapshot at version 10
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM snapshots WHERE aggregate_id = ?", Integer.class,
				written.id())).isEqualTo(1);

		// Pretend the Wallet class changed: store a bogus state under an older schema version.
		jdbc.update("UPDATE snapshots SET snapshot_schema_version = ?, state = '{\"balance\": -1}'::jsonb "
				+ "WHERE aggregate_id = ?", WalletSnapshot.SCHEMA_VERSION - 1, written.id());

		Wallet loaded = repository.load(written.id()).orElseThrow();
		assertThat(loaded.balance()).isEqualTo(written.balance());
		assertThat(loaded.committedVersion()).isEqualTo(15);
	}

	@Test
	void repositoryRejectsSnapshotWithMismatchedWalletId() {
		WalletRepository repository = new WalletRepository(eventStore, snapshotStore, serializer, 10);
		Wallet written = walletWithMovements(repository, 14);

		UUID differentWalletId = UUID.randomUUID();
		// Tamper snapshot JSON payload with a different wallet ID
		String tamperedJson = serializer.toJson(new WalletSnapshot(differentWalletId, written.ownerId(),
				written.currency(), 999999L, written.status()));
		jdbc.update("UPDATE snapshots SET state = ?::jsonb WHERE aggregate_id = ?", tamperedJson, written.id());

		// Falls back to full replay cleanly without throwing
		Wallet loaded = repository.load(written.id()).orElseThrow();
		assertThat(loaded.id()).isEqualTo(written.id());
		assertThat(loaded.balance()).isEqualTo(written.balance());
	}

	@Test
	void repositoryRejectsSnapshotWithWrongAggregateType() {
		WalletRepository repository = new WalletRepository(eventStore, snapshotStore, serializer, 10);
		Wallet written = walletWithMovements(repository, 14);

		jdbc.update("UPDATE snapshots SET aggregate_type = 'Order' WHERE aggregate_id = ?", written.id());

		// Falls back to full replay
		Wallet loaded = repository.load(written.id()).orElseThrow();
		assertThat(loaded.balance()).isEqualTo(written.balance());
		assertThat(loaded.committedVersion()).isEqualTo(15);
	}

	@Test
	void repositoryRejectsSnapshotAheadOfEventStream() {
		WalletRepository repository = new WalletRepository(eventStore, snapshotStore, serializer, 10);
		Wallet written = walletWithMovements(repository, 14); // total 15 events in DB

		// Corrupt snapshot version to be 999 (ahead of event store)
		jdbc.update("UPDATE snapshots SET version = 999 WHERE aggregate_id = ?", written.id());

		// Falls back to full replay from event store
		Wallet loaded = repository.load(written.id()).orElseThrow();
		assertThat(loaded.committedVersion()).isEqualTo(15);
		assertThat(loaded.balance()).isEqualTo(written.balance());
	}

	@Test
	void repositoryRejectsNonContiguousEventVersions() {
		WalletRepository repository = new WalletRepository(eventStore, snapshotStore, serializer, 100);
		UUID corruptedWalletId = UUID.randomUUID();

		// Insert version 1
		jdbc.update("""
				INSERT INTO events (event_id, aggregate_id, aggregate_type, version, event_type, schema_version,
				                    occurred_at, correlation_id, causation_id, payload)
				VALUES (?, ?, 'Wallet', 1, 'WalletOpened', 1, now(), ?, null, ?::jsonb)
				""", UUID.randomUUID(), corruptedWalletId, corruptedWalletId,
				serializer.toJson(new WalletOpened(corruptedWalletId, UUID.randomUUID(), Currency.VND)));

		// Insert version 3 directly (skipping version 2)
		jdbc.update("""
				INSERT INTO events (event_id, aggregate_id, aggregate_type, version, event_type, schema_version,
				                    occurred_at, correlation_id, causation_id, payload)
				VALUES (?, ?, 'Wallet', 3, 'WalletOpened', 1, now(), ?, null, ?::jsonb)
				""", UUID.randomUUID(), corruptedWalletId, corruptedWalletId,
				serializer.toJson(new WalletOpened(corruptedWalletId, UUID.randomUUID(), Currency.VND)));

		assertThatThrownBy(() -> repository.load(corruptedWalletId))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Non-contiguous event stream");
	}

	@Test
	void snapshotFailureDoesNotPreventEventSave() {
		// Create a repository with a broken snapshot store that always fails
		PostgresSnapshotStore failingSnapshotStore = new PostgresSnapshotStore(jdbc) {
			@Override
			public void save(UUID aggregateId, String aggregateType, long version, int schemaVersion, String stateJson) {
				throw new RuntimeException("Simulated snapshot storage failure");
			}
		};

		WalletRepository resilientRepository = new WalletRepository(eventStore, failingSnapshotStore, serializer, 1);
		Wallet wallet = Wallet.open(UUID.randomUUID(), UUID.randomUUID(), Currency.VND);

		// Saving triggers snapshot since snapshotInterval=1. It should NOT throw!
		resilientRepository.save(wallet);
		assertThat(wallet.committedVersion()).isEqualTo(1);

		// Event was committed in eventStore despite snapshot failure
		assertThat(eventStore.latestVersion(wallet.id())).isEqualTo(1);
	}

	@Test
	void constructorRejectsNonPositiveSnapshotInterval() {
		assertThatThrownBy(() -> new WalletRepository(eventStore, snapshotStore, serializer, 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new WalletRepository(eventStore, snapshotStore, serializer, -5))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void unknownWalletIsEmpty() {
		WalletRepository repository = new WalletRepository(eventStore, snapshotStore, serializer);
		assertThat(repository.load(UUID.randomUUID())).isEmpty();
	}
}
