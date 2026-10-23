package com.lakeserl.mini_banking.shared.eventsourcing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.domain.PendingEvent;
import com.lakeserl.mini_banking.wallet.domain.events.WalletCredited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletOpened;

@PostgresDataTest
class PostgresEventStoreTest {

	@Autowired
	EventStore store;

	@Autowired
	EventSerializer serializer;

	private static PendingEvent opened(UUID walletId) {
		return new PendingEvent(UUID.randomUUID(), new WalletOpened(walletId, UUID.randomUUID(), Currency.VND),
				walletId, null);
	}

	private static PendingEvent credited(UUID walletId, long amount) {
		UUID tx = UUID.randomUUID();
		return new PendingEvent(UUID.randomUUID(),
				new WalletCredited(walletId, Money.vnd(amount), tx, "BANK_SETTLEMENT", AccountType.SETTLEMENT), tx, null);
	}

	@Test
	void writesAndReadsEventsBackInOrderWithEnvelope() {
		UUID id = UUID.randomUUID();
		PendingEvent first = opened(id);
		PendingEvent second = credited(id, 1_000);
		store.append(id, "Wallet", 0, List.of(first, second));

		List<StoredEvent> events = store.load(id, 0);
		assertThat(events).hasSize(2);
		assertThat(events.get(0).version()).isEqualTo(1);
		assertThat(events.get(0).eventId()).isEqualTo(first.eventId());
		assertThat(events.get(0).event()).isEqualTo(first.event());
		assertThat(events.get(0).aggregateType()).isEqualTo("Wallet");
		assertThat(events.get(0).eventType()).isEqualTo("WalletOpened");
		assertThat(events.get(0).occurredAt()).isNotNull();
		assertThat(events.get(1).version()).isEqualTo(2);
		assertThat(events.get(1).event()).isEqualTo(second.event());
		assertThat(store.load(id, 1)).hasSize(1);
		assertThat(store.contains(first.eventId())).isTrue();
		assertThat(store.contains(UUID.randomUUID())).isFalse();
	}

	@Test
	void writingTheSameVersionTwiceThrowsOptimisticConcurrencyException() {
		UUID id = UUID.randomUUID();
		store.append(id, "Wallet", 0, List.of(opened(id)));
		assertThatThrownBy(() -> store.append(id, "Wallet", 0, List.of(credited(id, 5))))
				.isInstanceOf(OptimisticConcurrencyException.class);
		assertThat(store.load(id, 0)).hasSize(1);
	}

	@Test
	void skippingVersionsIsRejected() {
		UUID id = UUID.randomUUID();
		assertThatThrownBy(() -> store.append(id, "Wallet", 5, List.of(opened(id))))
				.isInstanceOf(OptimisticConcurrencyException.class);
	}

	@Test
	void sameEventIdTwiceThrowsDuplicateEventException() {
		UUID id = UUID.randomUUID();
		PendingEvent event = opened(id);
		store.append(id, "Wallet", 0, List.of(event));
		assertThatThrownBy(() -> store.append(id, "Wallet", 1, List.of(event)))
				.isInstanceOf(DuplicateEventException.class);
		assertThat(store.load(id, 0)).hasSize(1);
	}

	@Test
	void failedBatchIsRolledBackCompletely() {
		UUID id = UUID.randomUUID();
		PendingEvent first = opened(id);
		PendingEvent duplicateOfFirst = new PendingEvent(first.eventId(), credited(id, 1).event(), id, null);
		assertThatThrownBy(() -> store.append(id, "Wallet", 0, List.of(first, duplicateOfFirst)))
				.isInstanceOf(DuplicateEventException.class);
		assertThat(store.load(id, 0)).isEmpty();
	}

	@Test
	void twentyConcurrentWritersWithTheSameVersionOnlyOneWins() throws Exception {
		UUID id = UUID.randomUUID();
		int writers = 20;
		ExecutorService pool = Executors.newFixedThreadPool(writers);
		CountDownLatch ready = new CountDownLatch(writers);
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < writers; i++) {
				PendingEvent event = i == 0 ? opened(id) : credited(id, i);
				results.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					try {
						store.append(id, "Wallet", 0, List.of(event));
						return true;
					} catch (OptimisticConcurrencyException e) {
						return false;
					}
				}));
			}
			ready.await(10, TimeUnit.SECONDS);
			go.countDown();
			int winners = 0;
			for (Future<Boolean> result : results) {
				if (result.get(30, TimeUnit.SECONDS)) {
					winners++;
				}
			}
			assertThat(winners).isEqualTo(1);
			assertThat(store.load(id, 0)).hasSize(1);
		} finally {
			pool.shutdownNow();
		}
	}

	// ── Serializer round-trip ─────────────────────────────────────────────────

	@Test
	void serializerRoundTripForAllWalletEventTypes() {
		UUID walletId = UUID.randomUUID();
		UUID tx = UUID.randomUUID();

		var opened = new WalletOpened(walletId, UUID.randomUUID(), Currency.VND);
		var credited = new WalletCredited(walletId, Money.vnd(999), tx, "BANK_SETTLEMENT", AccountType.SETTLEMENT);

		assertThat(serializer.fromJson(serializer.toJson(opened), WalletOpened.class)).isEqualTo(opened);
		assertThat(serializer.fromJson(serializer.toJson(credited), WalletCredited.class)).isEqualTo(credited);

		// Round-trip through event store: write -> read -> deserialize
		store.append(walletId, "Wallet", 0, List.of(
				new PendingEvent(UUID.randomUUID(), opened, walletId, null),
				new PendingEvent(UUID.randomUUID(), credited, tx, null)));

		List<StoredEvent> loaded = store.load(walletId, 0);
		assertThat(loaded.get(0).event()).isEqualTo(opened);
		assertThat(loaded.get(1).event()).isEqualTo(credited);
	}

	@Test
	void deserializeUnknownEventTypeThrowsIllegalState() {
		assertThatThrownBy(() -> serializer.deserialize("NonExistentEventXyz", "{}"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Unknown event type");
	}

	@Test
	void latestVersionReturnsZeroForNewAggregateAndCorrectVersionAfterAppend() {
		UUID id = UUID.randomUUID();
		assertThat(store.latestVersion(id)).isEqualTo(0);

		store.append(id, "Wallet", 0, List.of(opened(id)));
		assertThat(store.latestVersion(id)).isEqualTo(1);

		store.append(id, "Wallet", 1, List.of(credited(id, 100)));
		assertThat(store.latestVersion(id)).isEqualTo(2);
	}

	@Test
	void findEventReturnsCorrectEnvelopeById() {
		UUID id = UUID.randomUUID();
		PendingEvent event = opened(id);
		store.append(id, "Wallet", 0, List.of(event));

		StoredEvent found = store.findEvent(event.eventId()).orElseThrow();
		assertThat(found.eventId()).isEqualTo(event.eventId());
		assertThat(found.aggregateId()).isEqualTo(id);
		assertThat(found.aggregateType()).isEqualTo("Wallet");
		assertThat(found.version()).isEqualTo(1);
		assertThat(found.event()).isEqualTo(event.event());
	}
}
