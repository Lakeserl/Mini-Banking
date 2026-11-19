package com.lakeserl.mini_banking.transfer.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.domain.PendingEvent;
import com.lakeserl.mini_banking.shared.eventsourcing.DuplicateEventException;
import com.lakeserl.mini_banking.shared.eventsourcing.EventSerializer;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.shared.eventsourcing.OptimisticConcurrencyException;
import com.lakeserl.mini_banking.shared.eventsourcing.PostgresSnapshotStore;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;
import com.lakeserl.mini_banking.transfer.domain.Transfer;
import com.lakeserl.mini_banking.transfer.domain.TransferStatus;
import com.lakeserl.mini_banking.transfer.domain.TransferType;
import com.lakeserl.mini_banking.transfer.domain.exceptions.InvalidTransferStateException;
import com.lakeserl.mini_banking.transfer.domain.exceptions.TransferNotFoundException;
import com.lakeserl.mini_banking.transfer.domain.exceptions.TransferPolicyViolationException;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferEventTypes;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferRepository;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferSagaStateStore;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.domain.events.WalletCredited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletDebited;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletEventTypes;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;

class TransferConcurrencyUnitTest {

	private static final int THREAD_COUNT = 100;
	private static final long INITIAL_SOURCE_VND = 10_000_000L;
	private static final long TRANSFER_AMOUNT_VND = 200_000L;

	@Test
	void hundredConcurrentTransfers_from10mWalletFor200kEach_produces50Completed50FailedAndExactZeroAnd10mBalances()
			throws Exception {
		MutableClock clock = new MutableClock(Instant.parse("2026-11-19T09:00:00Z"));
		ThreadSafeInMemoryEventStore eventStore = new ThreadSafeInMemoryEventStore(clock);
		ThreadSafeInMemorySagaStateStore sagaStateStore = new ThreadSafeInMemorySagaStateStore(clock);
		EventSerializer serializer = new EventSerializer(List.of(new WalletEventTypes(), new TransferEventTypes()));
		PostgresSnapshotStore snapshotStore = new PostgresSnapshotStore(mock(JdbcTemplate.class)) {
			@Override
			public Optional<Snapshot> find(UUID aggregateId, int schemaVersion) {
				return Optional.empty();
			}

			@Override
			public void save(UUID aggregateId, String aggregateType, long version, UUID lastEventId, int schemaVersion,
					String stateHash, String stateJson) {
			}
		};
		WalletRepository walletRepository = new WalletRepository(eventStore, snapshotStore, serializer);
		WalletCommandHandler walletHandler = new WalletCommandHandler(walletRepository, eventStore, 3);
		TransferRepository transferRepository = new TransferRepository(eventStore);
		TransactionTemplate txTemplate = new TransactionTemplate(new NoOpTransactionManager());
		TransferSagaOrchestrator orchestrator = new TransferSagaOrchestrator(transferRepository, sagaStateStore,
				walletRepository, walletHandler, eventStore, txTemplate, 25, 1L, 3L);
		SagaRecoveryScheduler recoveryScheduler = new SagaRecoveryScheduler(sagaStateStore, orchestrator,
				Duration.ofSeconds(30), 10, 100);

		UUID sourceWalletId = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		walletHandler.credit(sourceWalletId, UUID.randomUUID(), Money.vnd(INITIAL_SOURCE_VND),
				"BANK_SETTLEMENT", AccountType.SETTLEMENT);
		UUID destWalletId = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);

		CyclicBarrier startBarrier = new CyclicBarrier(THREAD_COUNT);
		CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
		ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
		List<UUID> transferIds = new ArrayList<>(THREAD_COUNT);
		for (int i = 0; i < THREAD_COUNT; i++) {
			transferIds.add(UUID.randomUUID());
		}

		try {
			List<Future<Transfer>> futures = new ArrayList<>(THREAD_COUNT);
			for (UUID transferId : transferIds) {
				futures.add(executor.submit(() -> {
					try {
						startBarrier.await(15, TimeUnit.SECONDS);
						return orchestrator.transfer(transferId, sourceWalletId, destWalletId,
								Money.vnd(TRANSFER_AMOUNT_VND));
					} finally {
						doneLatch.countDown();
					}
				}));
			}

			assertThat(doneLatch.await(30, TimeUnit.SECONDS)).isTrue();
			for (Future<Transfer> future : futures) {
				future.get(10, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
		}

		// If extreme contention left any saga non-terminal, advance clock > 30s and let SagaRecoveryScheduler finish them
		for (int sweep = 0; sweep < 5; sweep++) {
			clock.advance(Duration.ofSeconds(35));
			if (recoveryScheduler.recoverOnce() == 0) {
				break;
			}
		}

		int completedCount = 0;
		int failedCount = 0;
		for (UUID transferId : transferIds) {
			Transfer persisted = transferRepository.load(transferId).orElseThrow();
			TransferSagaStateStore.TransferSagaSummary summary = sagaStateStore.findById(transferId).orElseThrow();
			assertThat(persisted.status().isTerminal()).isTrue();
			assertThat(summary.status()).isEqualTo(persisted.status());
			if (persisted.status() == TransferStatus.COMPLETED) {
				completedCount++;
			} else if (persisted.status() == TransferStatus.FAILED) {
				failedCount++;
			}
		}

		assertThat(completedCount).isEqualTo(50);
		assertThat(failedCount).isEqualTo(50);

		Wallet sourceWallet = walletRepository.load(sourceWalletId).orElseThrow();
		Wallet destWallet = walletRepository.load(destWalletId).orElseThrow();
		assertThat(sourceWallet.balance()).isEqualTo(Money.vnd(0L));
		assertThat(destWallet.balance()).isEqualTo(Money.vnd(INITIAL_SOURCE_VND));
		assertThat(eventStore.sawNegativeWalletBalance.get()).isFalse();
	}

	private static final class MutableClock extends Clock {
		private volatile Instant current;

		MutableClock(Instant initial) {
			this.current = initial;
		}

		void advance(Duration duration) {
			this.current = this.current.plus(duration);
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return current;
		}
	}

	private static final class NoOpTransactionManager implements PlatformTransactionManager {
		@Override
		public TransactionStatus getTransaction(TransactionDefinition definition) {
			return new SimpleTransactionStatus();
		}

		@Override
		public void commit(TransactionStatus status) {
		}

		@Override
		public void rollback(TransactionStatus status) {
		}
	}

	private static final class ThreadSafeInMemoryEventStore implements EventStore {
		private final Map<UUID, List<StoredEvent>> streams = new ConcurrentHashMap<>();
		private final Map<UUID, StoredEvent> byEventId = new ConcurrentHashMap<>();
		private final Map<UUID, Object> aggregateLocks = new ConcurrentHashMap<>();
		private final Clock clock;
		final AtomicBoolean sawNegativeWalletBalance = new AtomicBoolean(false);

		ThreadSafeInMemoryEventStore(Clock clock) {
			this.clock = clock;
		}

		@Override
		public void append(UUID aggregateId, String aggregateType, long expectedVersion, List<PendingEvent> pending) {
			Object lock = aggregateLocks.computeIfAbsent(aggregateId, k -> new Object());
			synchronized (lock) {
				for (PendingEvent p : pending) {
					if (byEventId.containsKey(p.eventId())) {
						throw new DuplicateEventException(p.eventId(), null);
					}
				}
				List<StoredEvent> stream = streams.computeIfAbsent(aggregateId, k -> new ArrayList<>());
				long currentVersion = stream.isEmpty() ? 0L : stream.get(stream.size() - 1).version();
				if (currentVersion != expectedVersion) {
					throw new OptimisticConcurrencyException(
							"Expected version " + expectedVersion + " but was " + currentVersion);
				}
				long version = expectedVersion;
				for (PendingEvent p : pending) {
					version++;
					StoredEvent stored = new StoredEvent(p.eventId(), aggregateId, aggregateType, version,
							p.event().eventType(), 1, clock.instant(), p.correlationId(), p.causationId(), p.event());
					stream.add(stored);
					byEventId.put(p.eventId(), stored);
				}
				if ("Wallet".equals(aggregateType)) {
					long runningBalance = 0L;
					for (StoredEvent e : stream) {
						if (e.event() instanceof WalletCredited credited) {
							runningBalance += credited.amount().amount();
						} else if (e.event() instanceof WalletDebited debited) {
							runningBalance -= debited.amount().amount();
						}
						if (runningBalance < 0L) {
							sawNegativeWalletBalance.set(true);
						}
					}
				}
			}
		}

		@Override
		public List<StoredEvent> load(UUID aggregateId, long afterVersion) {
			Object lock = aggregateLocks.computeIfAbsent(aggregateId, k -> new Object());
			synchronized (lock) {
				List<StoredEvent> stream = streams.get(aggregateId);
				if (stream == null || stream.isEmpty()) {
					return List.of();
				}
				return stream.stream().filter(e -> e.version() > afterVersion).toList();
			}
		}

		@Override
		public boolean contains(UUID eventId) {
			return byEventId.containsKey(eventId);
		}

		@Override
		public Optional<StoredEvent> findEvent(UUID eventId) {
			return Optional.ofNullable(byEventId.get(eventId));
		}

		@Override
		public long latestVersion(UUID aggregateId) {
			Object lock = aggregateLocks.computeIfAbsent(aggregateId, k -> new Object());
			synchronized (lock) {
				List<StoredEvent> stream = streams.get(aggregateId);
				return (stream == null || stream.isEmpty()) ? 0L : stream.get(stream.size() - 1).version();
			}
		}
	}

	private static final class ThreadSafeInMemorySagaStateStore extends TransferSagaStateStore {
		private final Map<UUID, TransferSagaSummary> rows = new ConcurrentHashMap<>();
		private final Map<UUID, Object> walletAdvisoryLocks = new ConcurrentHashMap<>();
		private final Set<UUID> activeRefundOriginals = ConcurrentHashMap.newKeySet();
		private final Clock clock;

		ThreadSafeInMemorySagaStateStore(Clock clock) {
			super(mock(JdbcTemplate.class), clock);
			this.clock = clock;
		}

		@Override
		public boolean insertInitiated(UUID transferId, UUID sourceWalletId, UUID destinationWalletId, Money amount,
				TransferType type, UUID originalTransferId, long dailyLimitVnd) {
			Object lock = type == TransferType.STANDARD
					? walletAdvisoryLocks.computeIfAbsent(sourceWalletId, k -> new Object())
					: this;
			synchronized (lock) {
				if (rows.containsKey(transferId)) {
					return false;
				}
				if (type == TransferType.STANDARD) {
					long spent24h = sumOutgoingLast24Hours(sourceWalletId);
					long projected = Math.addExact(spent24h, amount.amount());
					if (projected > dailyLimitVnd) {
						throw new TransferPolicyViolationException(
								"Daily outgoing transfer limit of " + dailyLimitVnd + " VND exceeded for wallet "
										+ sourceWalletId);
					}
				} else if (type == TransferType.REFUND && originalTransferId != null) {
					if (!activeRefundOriginals.add(originalTransferId)) {
						throw new InvalidTransferStateException(
								"Transfer " + originalTransferId + " already has an active or completed refund");
					}
				}
				Instant now = clock.instant();
				rows.put(transferId, new TransferSagaSummary(transferId, sourceWalletId, destinationWalletId,
						amount.amount(), amount.currency(), type, originalTransferId, TransferStatus.INITIATED,
						0, null, now, now));
				return true;
			}
		}

		@Override
		public void updateStatus(UUID transferId, TransferStatus status, String failureReason) {
			rows.computeIfPresent(transferId, (id, existing) -> {
				if (existing.type() == TransferType.REFUND && existing.originalTransferId() != null
						&& (status == TransferStatus.FAILED || status == TransferStatus.REVERSED)) {
					activeRefundOriginals.remove(existing.originalTransferId());
				}
				return new TransferSagaSummary(existing.transferId(), existing.sourceWalletId(),
						existing.destinationWalletId(), existing.amount(), existing.currency(), existing.type(),
						existing.originalTransferId(), status, existing.retryCount(), failureReason,
						existing.createdAt(), clock.instant());
			});
		}

		@Override
		public List<StuckSaga> findStuck(Duration olderThan, int limit) {
			Instant cutoff = clock.instant().minus(olderThan);
			return rows.values().stream()
					.filter(r -> !r.status().isTerminal() && !r.updatedAt().isAfter(cutoff))
					.sorted(Comparator.comparing(TransferSagaSummary::updatedAt))
					.limit(limit)
					.map(r -> new StuckSaga(r.transferId(), r.status(), r.retryCount()))
					.toList();
		}

		@Override
		public int incrementRetry(UUID transferId) {
			TransferSagaSummary updated = rows.computeIfPresent(transferId, (id, existing) -> new TransferSagaSummary(
					existing.transferId(), existing.sourceWalletId(), existing.destinationWalletId(), existing.amount(),
					existing.currency(), existing.type(), existing.originalTransferId(), existing.status(),
					existing.retryCount() + 1, existing.failureReason(), existing.createdAt(), existing.updatedAt()));
			if (updated == null) {
				throw new TransferNotFoundException(transferId);
			}
			return updated.retryCount();
		}

		@Override
		public long sumOutgoingLast24Hours(UUID sourceWalletId) {
			Instant cutoff = clock.instant().minus(Duration.ofHours(24));
			return rows.values().stream()
					.filter(r -> r.sourceWalletId().equals(sourceWalletId)
							&& r.type() == TransferType.STANDARD
							&& (r.status() == TransferStatus.INITIATED
									|| r.status() == TransferStatus.DEBITED
									|| r.status() == TransferStatus.COMPLETED)
							&& !r.createdAt().isBefore(cutoff))
					.mapToLong(TransferSagaSummary::amount)
					.sum();
		}

		@Override
		public Optional<TransferSagaSummary> findById(UUID transferId) {
			return Optional.ofNullable(rows.get(transferId));
		}
	}
}
