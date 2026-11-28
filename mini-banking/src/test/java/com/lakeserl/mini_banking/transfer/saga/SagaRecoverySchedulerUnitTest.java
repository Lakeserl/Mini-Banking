package com.lakeserl.mini_banking.transfer.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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
import com.lakeserl.mini_banking.transfer.domain.exceptions.TransferPolicyViolationException;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferEventTypes;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferRepository;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferSagaStateStore;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletEventTypes;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;

class SagaRecoverySchedulerUnitTest {

	private static final Instant T0 = Instant.parse("2026-11-19T08:00:00Z");

	private MutableClock clock;
	private InMemoryEventStore eventStore;
	private InMemorySagaStateStore sagaStateStore;
	private WalletRepository walletRepository;
	private WalletCommandHandler walletHandler;
	private TransferRepository transferRepository;
	private TransactionTemplate txTemplate;
	private TransferSagaOrchestrator orchestrator;
	private SagaRecoveryScheduler scheduler;

	@BeforeEach
	void setUp() {
		clock = new MutableClock(T0);
		eventStore = new InMemoryEventStore();
		sagaStateStore = new InMemorySagaStateStore(clock);
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
		walletRepository = new WalletRepository(eventStore, snapshotStore, serializer);
		walletHandler = new WalletCommandHandler(walletRepository, eventStore, 1);
		transferRepository = new TransferRepository(eventStore);
		txTemplate = new TransactionTemplate(new NoOpTransactionManager());
		orchestrator = new TransferSagaOrchestrator(transferRepository, sagaStateStore, walletRepository,
				walletHandler, eventStore, txTemplate, 1, 0L, 0L);
		scheduler = new SagaRecoveryScheduler(sagaStateStore, orchestrator,
				Duration.ofSeconds(SagaRecoveryScheduler.DEFAULT_STUCK_THRESHOLD_SECONDS),
				SagaRecoveryScheduler.DEFAULT_MAX_RETRIES,
				SagaRecoveryScheduler.DEFAULT_BATCH_SIZE);
	}

	private UUID openFundedWallet(long initialVnd) {
		UUID walletId = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		if (initialVnd > 0) {
			walletHandler.credit(walletId, UUID.randomUUID(), Money.vnd(initialVnd),
					"BANK_SETTLEMENT", AccountType.SETTLEMENT);
		}
		return walletId;
	}

	@Test
	void recoverOnce_stuckInInitiatedOver30s_completesDebitAndCreditToCompletedAndIsIdempotent() {
		UUID sourceId = openFundedWallet(500_000L);
		UUID destId = openFundedWallet(100_000L);
		UUID transferId = UUID.randomUUID();

		// Simulate crash right after INITIATED was persisted at T0
		sagaStateStore.insertInitiated(transferId, sourceId, destId, Money.vnd(200_000L),
				TransferType.STANDARD, null, TransferSagaOrchestrator.MAX_DAILY_OUTGOING_VND);
		transferRepository.save(Transfer.initiate(transferId, sourceId, destId, Money.vnd(200_000L),
				TransferType.STANDARD, null));

		// Advance clock > 30s
		clock.advance(Duration.ofSeconds(31));

		int processed = scheduler.recoverOnce();

		assertThat(processed).isEqualTo(1);
		assertThat(transferRepository.load(transferId)).get().extracting(Transfer::status)
				.isEqualTo(TransferStatus.COMPLETED);
		assertThat(sagaStateStore.findById(transferId)).get()
				.satisfies(s -> {
					assertThat(s.status()).isEqualTo(TransferStatus.COMPLETED);
					assertThat(s.retryCount()).isEqualTo(1);
				});
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(300_000L));
		assertThat(walletRepository.load(destId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(300_000L));

		// Repeated recoverOnce() never double-debits or double-credits
		clock.advance(Duration.ofSeconds(60));
		assertThat(scheduler.recoverOnce()).isZero();
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(300_000L));
		assertThat(walletRepository.load(destId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(300_000L));
	}

	@Test
	void recoverOnce_stuckInInitiatedWhereWalletDebitedAlreadyExists_advancesToDebitedAndCompletesCreditEvenAtMaxRetries() {
		UUID sourceId = openFundedWallet(500_000L);
		UUID destId = openFundedWallet(100_000L);
		UUID transferId = UUID.randomUUID();

		// Crash between WalletDebited commit and Transfer::markDebitCompleted
		sagaStateStore.insertInitiated(transferId, sourceId, destId, Money.vnd(150_000L),
				TransferType.STANDARD, null, TransferSagaOrchestrator.MAX_DAILY_OUTGOING_VND);
		transferRepository.save(Transfer.initiate(transferId, sourceId, destId, Money.vnd(150_000L),
				TransferType.STANDARD, null));
		walletHandler.debit(sourceId, transferId, Money.vnd(150_000L),
				TransferSagaOrchestrator.TRANSFER_CLEARING_ACCOUNT, AccountType.CLEARING);

		// Bump retry count so next sweep hits attempt == 3 >= maxRetries (3)
		sagaStateStore.incrementRetry(transferId);
		sagaStateStore.incrementRetry(transferId);
		clock.advance(Duration.ofSeconds(35));

		int processed = scheduler.recoverOnce();

		assertThat(processed).isEqualTo(1);
		assertThat(transferRepository.load(transferId)).get().extracting(Transfer::status)
				.isEqualTo(TransferStatus.COMPLETED);
		assertThat(sagaStateStore.findById(transferId)).get().extracting(TransferSagaStateStore.TransferSagaSummary::status)
				.isEqualTo(TransferStatus.COMPLETED);
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(350_000L));
		assertThat(walletRepository.load(destId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(250_000L));
	}

	@Test
	void recoverOnce_stuckInDebitedOver30s_creditsDestinationWithoutDoubleDebit() {
		UUID sourceId = openFundedWallet(500_000L);
		UUID destId = openFundedWallet(100_000L);
		UUID transferId = UUID.randomUUID();

		sagaStateStore.insertInitiated(transferId, sourceId, destId, Money.vnd(200_000L),
				TransferType.STANDARD, null, TransferSagaOrchestrator.MAX_DAILY_OUTGOING_VND);
		Transfer transfer = Transfer.initiate(transferId, sourceId, destId, Money.vnd(200_000L),
				TransferType.STANDARD, null);
		walletHandler.debit(sourceId, transferId, Money.vnd(200_000L),
				TransferSagaOrchestrator.TRANSFER_CLEARING_ACCOUNT, AccountType.CLEARING);
		transfer.markDebitCompleted();
		transferRepository.save(transfer);
		sagaStateStore.updateStatus(transferId, TransferStatus.DEBITED, null);

		clock.advance(Duration.ofSeconds(40));

		int processed = scheduler.recoverOnce();

		assertThat(processed).isEqualTo(1);
		assertThat(transferRepository.load(transferId)).get().extracting(Transfer::status)
				.isEqualTo(TransferStatus.COMPLETED);
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(300_000L));
		assertThat(walletRepository.load(destId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(300_000L));
	}

	@Test
	void recoverOnce_stuckInDebitedWithLockedDestinationWallet_compensatesSourceToReversed() {
		UUID sourceId = openFundedWallet(500_000L);
		UUID destId = openFundedWallet(100_000L);
		UUID transferId = UUID.randomUUID();

		sagaStateStore.insertInitiated(transferId, sourceId, destId, Money.vnd(200_000L),
				TransferType.STANDARD, null, TransferSagaOrchestrator.MAX_DAILY_OUTGOING_VND);
		Transfer transfer = Transfer.initiate(transferId, sourceId, destId, Money.vnd(200_000L),
				TransferType.STANDARD, null);
		walletHandler.debit(sourceId, transferId, Money.vnd(200_000L),
				TransferSagaOrchestrator.TRANSFER_CLEARING_ACCOUNT, AccountType.CLEARING);
		transfer.markDebitCompleted();
		transferRepository.save(transfer);
		sagaStateStore.updateStatus(transferId, TransferStatus.DEBITED, null);

		walletHandler.lockWallet(destId, "frozen destination");
		clock.advance(Duration.ofSeconds(31));

		int processed = scheduler.recoverOnce();

		assertThat(processed).isEqualTo(1);
		assertThat(transferRepository.load(transferId)).get().extracting(Transfer::status)
				.isEqualTo(TransferStatus.REVERSED);
		assertThat(sagaStateStore.findById(transferId)).get().extracting(TransferSagaStateStore.TransferSagaSummary::status)
				.isEqualTo(TransferStatus.REVERSED);
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(500_000L));
		assertThat(walletRepository.load(destId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(100_000L));
	}

	@Test
	void recoverOnce_stuckInDebitedWithTransientCreditFailureAcross3Sweeps_staysDebitedUntilSweep3TriggersCompensation() {
		UUID sourceId = openFundedWallet(500_000L);
		UUID destId = openFundedWallet(100_000L);
		UUID transferId = UUID.randomUUID();

		// Force destination wallet credit contention so initial transfer stops in DEBITED
		eventStore.failWalletAppendsForAggregate = destId;
		Transfer initial = orchestrator.transfer(transferId, sourceId, destId, Money.vnd(200_000L));
		assertThat(initial.status()).isEqualTo(TransferStatus.DEBITED);
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(300_000L));

		clock.advance(Duration.ofSeconds(31));

		// Sweep 1 (attempt = 1 < 3): still failing -> stays DEBITED and backs off for 30s
		assertThat(scheduler.recoverOnce()).isEqualTo(1);
		assertThat(transferRepository.load(transferId)).get().extracting(Transfer::status)
				.isEqualTo(TransferStatus.DEBITED);
		assertThat(sagaStateStore.findById(transferId)).get().extracting(TransferSagaStateStore.TransferSagaSummary::retryCount)
				.isEqualTo(1);
		assertThat(scheduler.recoverOnce()).isZero();

		clock.advance(Duration.ofSeconds(31));

		// Sweep 2 (attempt = 2 < 3): still failing -> stays DEBITED and backs off for 30s
		assertThat(scheduler.recoverOnce()).isEqualTo(1);
		assertThat(transferRepository.load(transferId)).get().extracting(Transfer::status)
				.isEqualTo(TransferStatus.DEBITED);
		assertThat(sagaStateStore.findById(transferId)).get().extracting(TransferSagaStateStore.TransferSagaSummary::retryCount)
				.isEqualTo(2);
		assertThat(scheduler.recoverOnce()).isZero();

		clock.advance(Duration.ofSeconds(31));

		// Sweep 3 (attempt = 3 >= maxRetries): triggers compensation on sourceId -> REVERSED
		assertThat(scheduler.recoverOnce()).isEqualTo(1);
		assertThat(transferRepository.load(transferId)).get().extracting(Transfer::status)
				.isEqualTo(TransferStatus.REVERSED);
		assertThat(sagaStateStore.findById(transferId)).get()
				.satisfies(s -> {
					assertThat(s.status()).isEqualTo(TransferStatus.REVERSED);
					assertThat(s.retryCount()).isEqualTo(3);
				});
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(500_000L));
	}

	@Test
	void recoverOnce_stuckInInitiatedWithTransientDebitFailureAcross3Sweeps_staysInitiatedUntilSweep3MarksFailed() {
		UUID sourceId = openFundedWallet(500_000L);
		UUID destId = openFundedWallet(100_000L);
		UUID transferId = UUID.randomUUID();

		// Force source wallet debit contention so initial transfer stays INITIATED without WalletDebited
		eventStore.failWalletAppendsForAggregate = sourceId;
		Transfer initial = orchestrator.transfer(transferId, sourceId, destId, Money.vnd(200_000L));
		assertThat(initial.status()).isEqualTo(TransferStatus.INITIATED);

		clock.advance(Duration.ofSeconds(31));

		// Sweep 1 (attempt = 1 < 3): stays INITIATED and backs off for 30s
		assertThat(scheduler.recoverOnce()).isEqualTo(1);
		assertThat(transferRepository.load(transferId)).get().extracting(Transfer::status)
				.isEqualTo(TransferStatus.INITIATED);
		assertThat(scheduler.recoverOnce()).isZero();

		clock.advance(Duration.ofSeconds(31));

		// Sweep 2 (attempt = 2 < 3): stays INITIATED and backs off for 30s
		assertThat(scheduler.recoverOnce()).isEqualTo(1);
		assertThat(transferRepository.load(transferId)).get().extracting(Transfer::status)
				.isEqualTo(TransferStatus.INITIATED);
		assertThat(scheduler.recoverOnce()).isZero();

		clock.advance(Duration.ofSeconds(31));

		// Sweep 3 (attempt = 3 >= maxRetries): marks FAILED
		assertThat(scheduler.recoverOnce()).isEqualTo(1);
		assertThat(transferRepository.load(transferId)).get().extracting(Transfer::status)
				.isEqualTo(TransferStatus.FAILED);
		assertThat(sagaStateStore.findById(transferId)).get()
				.satisfies(s -> {
					assertThat(s.status()).isEqualTo(TransferStatus.FAILED);
					assertThat(s.retryCount()).isEqualTo(3);
					assertThat(s.failureReason()).isEqualTo("DEBIT_FAILED");
				});
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(500_000L));
	}

	@Test
	void recoverOnce_stuckInReversingOver30s_completesCompensationToReversed() {
		UUID sourceId = openFundedWallet(500_000L);
		UUID destId = openFundedWallet(100_000L);
		UUID transferId = UUID.randomUUID();

		sagaStateStore.insertInitiated(transferId, sourceId, destId, Money.vnd(200_000L),
				TransferType.STANDARD, null, TransferSagaOrchestrator.MAX_DAILY_OUTGOING_VND);
		Transfer transfer = Transfer.initiate(transferId, sourceId, destId, Money.vnd(200_000L),
				TransferType.STANDARD, null);
		walletHandler.debit(sourceId, transferId, Money.vnd(200_000L),
				TransferSagaOrchestrator.TRANSFER_CLEARING_ACCOUNT, AccountType.CLEARING);
		transfer.markDebitCompleted();
		transfer.startCompensation("destination locked mid-saga");
		transferRepository.save(transfer);
		sagaStateStore.updateStatus(transferId, TransferStatus.REVERSING, "destination locked mid-saga");

		clock.advance(Duration.ofSeconds(31));

		int processed = scheduler.recoverOnce();

		assertThat(processed).isEqualTo(1);
		assertThat(transferRepository.load(transferId)).get().extracting(Transfer::status)
				.isEqualTo(TransferStatus.REVERSED);
		assertThat(sagaStateStore.findById(transferId)).get().extracting(TransferSagaStateStore.TransferSagaSummary::status)
				.isEqualTo(TransferStatus.REVERSED);
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(500_000L));
	}

	@Test
	void recoverOnce_transferUpdatedLessThan30sAgo_isIgnored() {
		UUID sourceId = openFundedWallet(500_000L);
		UUID destId = openFundedWallet(100_000L);
		UUID transferId = UUID.randomUUID();

		sagaStateStore.insertInitiated(transferId, sourceId, destId, Money.vnd(200_000L),
				TransferType.STANDARD, null, TransferSagaOrchestrator.MAX_DAILY_OUTGOING_VND);
		transferRepository.save(Transfer.initiate(transferId, sourceId, destId, Money.vnd(200_000L),
				TransferType.STANDARD, null));

		// Only 29 seconds elapsed (< 30s threshold)
		clock.advance(Duration.ofSeconds(29));

		assertThat(scheduler.recoverOnce()).isZero();
		assertThat(transferRepository.load(transferId)).get().extracting(Transfer::status)
				.isEqualTo(TransferStatus.INITIATED);
	}

	@Test
	void recoverOnce_isolatedFailureOnOneStuckSaga_doesNotPreventSubsequentStuckSagasInBatchFromRecovering() {
		UUID sourceId = openFundedWallet(500_000L);
		UUID destId = openFundedWallet(100_000L);

		// First stuck saga is corrupted (exists in sagaStateStore, but missing in Transfer event stream -> throws TransferNotFoundException)
		UUID brokenTransferId = UUID.randomUUID();
		sagaStateStore.insertInitiated(brokenTransferId, sourceId, destId, Money.vnd(50_000L),
				TransferType.STANDARD, null, TransferSagaOrchestrator.MAX_DAILY_OUTGOING_VND);

		clock.advance(Duration.ofSeconds(1));

		// Second stuck saga is valid and should recover cleanly
		UUID healthyTransferId = UUID.randomUUID();
		sagaStateStore.insertInitiated(healthyTransferId, sourceId, destId, Money.vnd(200_000L),
				TransferType.STANDARD, null, TransferSagaOrchestrator.MAX_DAILY_OUTGOING_VND);
		transferRepository.save(Transfer.initiate(healthyTransferId, sourceId, destId, Money.vnd(200_000L),
				TransferType.STANDARD, null));

		clock.advance(Duration.ofSeconds(35));

		// schedule() delegates to recoverOnce() and recovers the second saga despite the first throwing
		scheduler.schedule();

		assertThat(transferRepository.load(healthyTransferId)).get().extracting(Transfer::status)
				.isEqualTo(TransferStatus.COMPLETED);
		assertThat(walletRepository.load(sourceId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(300_000L));
		assertThat(walletRepository.load(destId)).get().extracting(Wallet::balance)
				.isEqualTo(Money.vnd(300_000L));
	}

	@Test
	void recoverOnce_skipsResumeWhenIncrementRetryReturnsEmptyBecauseSagaConcurrentlyCompleted() {
		TransferSagaStateStore raceStore = mock(TransferSagaStateStore.class);
		TransferSagaOrchestrator spyOrchestrator = mock(TransferSagaOrchestrator.class);
		UUID transferId = UUID.randomUUID();
		when(raceStore.findStuck(any(Duration.class), anyInt()))
				.thenReturn(List.of(new TransferSagaStateStore.StuckSaga(transferId, TransferStatus.DEBITED, 0)));
		when(raceStore.incrementRetry(transferId)).thenReturn(OptionalInt.empty());
		SagaRecoveryScheduler raceScheduler = new SagaRecoveryScheduler(
				raceStore, spyOrchestrator, Duration.ofSeconds(30), 3, 50);

		assertThat(raceScheduler.recoverOnce()).isZero();
		verify(spyOrchestrator, never()).resume(any(UUID.class), anyInt(), anyInt());
	}

	@Test
	void schedule_catchesTransientRuntimeExceptionFromFindStuckWithoutPropagating() {
		TransferSagaStateStore failingStore = mock(TransferSagaStateStore.class);
		when(failingStore.findStuck(any(Duration.class), anyInt()))
				.thenThrow(new IllegalStateException("database temporarily unreachable"));
		SagaRecoveryScheduler resilientScheduler = new SagaRecoveryScheduler(failingStore, orchestrator,
				Duration.ofSeconds(30), 3, 50);

		assertThatCode(resilientScheduler::schedule).doesNotThrowAnyException();
	}

	@Test
	void constructorAndConditionalPropertyBinding_validatesArgumentsAndRespectsRecoveryEnabledFlag() {
		assertThatThrownBy(() -> new SagaRecoveryScheduler(sagaStateStore, orchestrator, 0L, 3, 50))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SagaRecoveryScheduler(sagaStateStore, orchestrator, -5L, 3, 50))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SagaRecoveryScheduler(sagaStateStore, orchestrator, null, 3, 50))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SagaRecoveryScheduler(sagaStateStore, orchestrator, Duration.ofSeconds(30), -1, 50))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SagaRecoveryScheduler(sagaStateStore, orchestrator, Duration.ofSeconds(30), 3, 0))
				.isInstanceOf(IllegalArgumentException.class);

		ApplicationContextRunner runner = new ApplicationContextRunner()
				.withBean(TransferSagaStateStore.class, () -> sagaStateStore)
				.withBean(TransferSagaOrchestrator.class, () -> orchestrator)
				.withBean(SagaRecoveryScheduler.class);

		runner.withPropertyValues(
						"transfer.recovery.stuck-threshold-seconds=30",
						"transfer.recovery.max-retries=3",
						"transfer.recovery.batch-size=50")
				.run(ctx -> assertThat(ctx).hasSingleBean(SagaRecoveryScheduler.class));

		runner.withPropertyValues("transfer.recovery.enabled=false")
				.run(ctx -> assertThat(ctx).doesNotHaveBean(SagaRecoveryScheduler.class));
	}

	private static final class MutableClock extends Clock {
		private Instant current;

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

	private final class InMemoryEventStore implements EventStore {
		private final List<StoredEvent> events = new ArrayList<>();
		UUID failWalletAppendsForAggregate;

		@Override
		public void append(UUID aggregateId, String aggregateType, long expectedVersion, List<PendingEvent> pending) {
			if ("Wallet".equals(aggregateType) && aggregateId.equals(failWalletAppendsForAggregate)) {
				throw new OptimisticConcurrencyException("Simulated wallet contention for " + aggregateId);
			}
			for (PendingEvent p : pending) {
				if (contains(p.eventId())) {
					throw new DuplicateEventException(p.eventId(), null);
				}
			}
			long currentVersion = latestVersion(aggregateId);
			if (currentVersion != expectedVersion) {
				throw new OptimisticConcurrencyException(
						"Expected version " + expectedVersion + " but was " + currentVersion);
			}
			long version = expectedVersion;
			for (PendingEvent p : pending) {
				version++;
				events.add(new StoredEvent(p.eventId(), aggregateId, aggregateType, version, p.event().eventType(), 1,
						clock.instant(), p.correlationId(), p.causationId(), p.event()));
			}
		}

		@Override
		public List<StoredEvent> load(UUID aggregateId, long afterVersion) {
			return events.stream()
					.filter(e -> e.aggregateId().equals(aggregateId) && e.version() > afterVersion)
					.toList();
		}

		@Override
		public boolean contains(UUID eventId) {
			return events.stream().anyMatch(e -> e.eventId().equals(eventId));
		}

		@Override
		public Optional<StoredEvent> findEvent(UUID eventId) {
			return events.stream().filter(e -> e.eventId().equals(eventId)).findFirst();
		}

		@Override
		public long latestVersion(UUID aggregateId) {
			return events.stream().filter(e -> e.aggregateId().equals(aggregateId))
					.mapToLong(StoredEvent::version).max().orElse(0L);
		}
	}

	private static final class InMemorySagaStateStore extends TransferSagaStateStore {
		private final Map<UUID, TransferSagaSummary> rows = new HashMap<>();
		private final Clock clock;

		InMemorySagaStateStore(Clock clock) {
			super(mock(JdbcTemplate.class), clock);
			this.clock = clock;
		}

		@Override
		public boolean insertInitiated(UUID transferId, UUID sourceWalletId, UUID destinationWalletId, Money amount,
				TransferType type, UUID originalTransferId, long dailyLimitVnd) {
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
				boolean activeRefundExists = rows.values().stream()
						.anyMatch(r -> originalTransferId.equals(r.originalTransferId())
								&& r.status() != TransferStatus.FAILED
								&& r.status() != TransferStatus.REVERSED);
				if (activeRefundExists) {
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

		@Override
		public void updateStatus(UUID transferId, TransferStatus status, String failureReason) {
			TransferSagaSummary existing = rows.get(transferId);
			if (existing != null) {
				rows.put(transferId, new TransferSagaSummary(existing.transferId(), existing.sourceWalletId(),
						existing.destinationWalletId(), existing.amount(), existing.currency(), existing.type(),
						existing.originalTransferId(), status, existing.retryCount(), failureReason,
						existing.createdAt(), clock.instant()));
			}
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
		public OptionalInt incrementRetry(UUID transferId) {
			TransferSagaSummary existing = rows.get(transferId);
			if (existing == null || existing.status().isTerminal()) {
				return OptionalInt.empty();
			}
			int next = existing.retryCount() + 1;
			rows.put(transferId, new TransferSagaSummary(existing.transferId(), existing.sourceWalletId(),
					existing.destinationWalletId(), existing.amount(), existing.currency(), existing.type(),
					existing.originalTransferId(), existing.status(), next, existing.failureReason(),
					existing.createdAt(), clock.instant()));
			return OptionalInt.of(next);
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
