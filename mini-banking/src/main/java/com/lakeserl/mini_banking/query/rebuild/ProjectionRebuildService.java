package com.lakeserl.mini_banking.query.rebuild;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.lakeserl.mini_banking.ledger.projection.LedgerProjector;
import com.lakeserl.mini_banking.query.projection.BalanceProjector;
import com.lakeserl.mini_banking.query.projection.TransactionHistoryProjector;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.shared.eventsourcing.GlobalStoredEvent;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;

/**
 * Rebuilds all read projections ({@code ledger_entries}, {@code wallet_balances},
 * {@code transaction_history}, {@code daily_reports}) directly from PostgreSQL {@code events}
 * ordered by {@code global_seq ASC}, independent of Kafka retention.
 *
 * <p>Operational assumption: {@link MessageListenerContainer#stop()} only halts Kafka consumers on
 * the local JVM, so this rebuild is safe only in single-instance deployments (or when peer
 * instances' consumers are stopped externally) and should be run during a maintenance window.
 */
@Service
public class ProjectionRebuildService {

	private static final Logger log = LoggerFactory.getLogger(ProjectionRebuildService.class);

	public static final int DEFAULT_BATCH_SIZE = 500;
	private static final long REBUILD_ADVISORY_LOCK_KEY = 7283910465192837465L;
	private static final String TRY_ADVISORY_LOCK_SQL = "SELECT pg_try_advisory_xact_lock(?)";

	public record RebuildResult(long eventsReplayed, long durationMs, Instant completedAt) {
	}

	public static class RebuildInProgressException extends RuntimeException {
		public RebuildInProgressException(String message) {
			super(message);
		}
	}

	// DELETE FROM avoids PG16 sequence-ownership failures on TRUNCATE ... RESTART IDENTITY under least-privilege app roles.
	private static final List<String> CLEAR_PROJECTION_TABLES_SQL = List.of(
			"DELETE FROM ledger_entries",
			"DELETE FROM wallet_balances",
			"DELETE FROM transaction_history",
			"DELETE FROM daily_reports");

	private static final String DELETE_PROCESSED_SQL = """
			DELETE FROM processed_events WHERE consumer_group IN (?, ?, ?)
			""";

	private final EventStore eventStore;
	private final JdbcTemplate jdbc;
	private final TransactionTemplate rebuildTx;
	private final LedgerProjector ledgerProjector;
	private final BalanceProjector balanceProjector;
	private final TransactionHistoryProjector txHistoryProjector;
	private final ObjectProvider<KafkaListenerEndpointRegistry> listenerRegistryProvider;
	private final Clock clock;
	private final int batchSize;
	private final AtomicBoolean running = new AtomicBoolean(false);

	public ProjectionRebuildService(
			EventStore eventStore,
			JdbcTemplate jdbc,
			TransactionTemplate tx,
			LedgerProjector ledgerProjector,
			BalanceProjector balanceProjector,
			TransactionHistoryProjector txHistoryProjector,
			ObjectProvider<KafkaListenerEndpointRegistry> listenerRegistryProvider,
			Clock clock,
			@Value("${query.rebuild.batch-size:" + DEFAULT_BATCH_SIZE + "}") int batchSize) {
		this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
		Objects.requireNonNull(tx, "tx");
		if (tx.getTransactionManager() != null) {
			TransactionTemplate isolatedTx = new TransactionTemplate(tx.getTransactionManager());
			isolatedTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
			isolatedTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
			this.rebuildTx = isolatedTx;
		} else {
			this.rebuildTx = tx;
		}
		this.ledgerProjector = Objects.requireNonNull(ledgerProjector, "ledgerProjector");
		this.balanceProjector = Objects.requireNonNull(balanceProjector, "balanceProjector");
		this.txHistoryProjector = Objects.requireNonNull(txHistoryProjector, "txHistoryProjector");
		this.listenerRegistryProvider = Objects.requireNonNull(listenerRegistryProvider, "listenerRegistryProvider");
		this.clock = Objects.requireNonNull(clock, "clock");
		if (batchSize <= 0) {
			throw new IllegalArgumentException("query.rebuild.batch-size must be positive");
		}
		this.batchSize = batchSize;
	}

	public RebuildResult rebuildAll() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Projection rebuild must not be invoked inside an active transaction");
		}
		if (!running.compareAndSet(false, true)) {
			throw new RebuildInProgressException("Projection rebuild is already in progress");
		}
		Instant startedAt = clock.instant();
		List<MessageListenerContainer> stoppedContainers = new ArrayList<>();
		try {
			stopRunningListeners(stoppedContainers);
			Long replayedObj = rebuildTx.execute(status -> {
				Boolean acquired = jdbc.queryForObject(TRY_ADVISORY_LOCK_SQL, Boolean.class, REBUILD_ADVISORY_LOCK_KEY);
				if (!Boolean.TRUE.equals(acquired)) {
					throw new RebuildInProgressException("Projection rebuild is already in progress");
				}
				for (String sql : CLEAR_PROJECTION_TABLES_SQL) {
					jdbc.update(sql);
				}
				jdbc.update(DELETE_PROCESSED_SQL,
						ledgerProjector.consumerGroup(),
						balanceProjector.consumerGroup(),
						txHistoryProjector.consumerGroup());

				long lastSeq = 0L;
				long replayed = 0L;
				while (true) {
					List<GlobalStoredEvent> batch = eventStore.loadBatch(lastSeq, batchSize);
					if (batch.isEmpty()) {
						break;
					}
					for (GlobalStoredEvent item : batch) {
						dispatch(item.storedEvent());
						lastSeq = item.globalSeq();
						replayed++;
					}
				}
				return replayed;
			});

			long replayed = replayedObj != null ? replayedObj : 0L;
			Instant completedAt = clock.instant();
			long durationMs = Math.max(0L, Duration.between(startedAt, completedAt).toMillis());
			log.info("Projection rebuild completed: eventsReplayed={}, durationMs={}", replayed, durationMs);
			return new RebuildResult(replayed, durationMs, completedAt);
		} finally {
			startListeners(stoppedContainers);
			running.set(false);
		}
	}

	private void dispatch(StoredEvent stored) {
		switch (stored.aggregateType()) {
			case "Wallet" -> {
				ledgerProjector.project(stored.eventId(), stored.event(), stored.occurredAt());
				balanceProjector.project(stored.eventId(), stored.event(), stored.version(), stored.occurredAt());
			}
			case "Deposit", "Withdrawal", "Transfer" ->
				txHistoryProjector.project(stored.eventId(), stored.event(), stored.occurredAt());
			default -> log.warn("Skipping unknown aggregateType during projection rebuild: {}", stored.aggregateType());
		}
	}

	private void stopRunningListeners(List<MessageListenerContainer> stopped) {
		KafkaListenerEndpointRegistry registry = listenerRegistryProvider.getIfAvailable();
		if (registry == null) {
			return;
		}
		for (MessageListenerContainer container : registry.getListenerContainers()) {
			if (container.isRunning()) {
				container.stop();
				stopped.add(container);
			}
		}
	}

	private void startListeners(List<MessageListenerContainer> containers) {
		for (MessageListenerContainer container : containers) {
			try {
				container.start();
			} catch (RuntimeException ex) {
				log.error("Failed to start Kafka listener container after rebuild", ex);
			}
		}
	}
}
