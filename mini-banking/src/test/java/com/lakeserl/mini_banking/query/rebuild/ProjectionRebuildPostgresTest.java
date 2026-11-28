package com.lakeserl.mini_banking.query.rebuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.transaction.support.TransactionTemplate;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.deposit.command.DepositCommandHandler;
import com.lakeserl.mini_banking.deposit.domain.events.DepositConfirmed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositFailed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositRequested;
import com.lakeserl.mini_banking.ledger.projection.LedgerProjector;
import com.lakeserl.mini_banking.ledger.projection.LedgerQueryRepository;
import com.lakeserl.mini_banking.query.api.QueryRepository;
import com.lakeserl.mini_banking.query.projection.BalanceProjector;
import com.lakeserl.mini_banking.query.projection.TransactionHistoryProjector;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.EventSerializer;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.shared.eventsourcing.GlobalStoredEvent;
import com.lakeserl.mini_banking.transfer.saga.TransferSagaOrchestrator;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;

@PostgresDataTest
class ProjectionRebuildPostgresTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	DataSource dataSource;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	EventStore eventStore;

	@Autowired
	LedgerProjector ledgerProjector;

	@Autowired
	BalanceProjector balanceProjector;

	@Autowired
	TransactionHistoryProjector txHistoryProjector;

	@Autowired
	ObjectProvider<KafkaListenerEndpointRegistry> listenerRegistryProvider;

	@Autowired
	Clock clock;

	@Autowired
	WalletCommandHandler walletCommands;

	@Autowired
	DepositCommandHandler depositCommands;

	@Autowired
	TransferSagaOrchestrator transferOrchestrator;

	@Autowired
	ProjectionRebuildService rebuildService;

	@Autowired
	QueryRepository queryRepository;

	@Autowired
	LedgerQueryRepository ledgerQueryRepository;

	@Autowired
	EventSerializer serializer;

	@BeforeEach
	void clean() {
		jdbc.update("TRUNCATE TABLE ledger_entries, wallet_balances, transaction_history, daily_reports, processed_events, transfer_saga_state RESTART IDENTITY");
	}

	@Test
	void rebuildFromEventStoreMatchesAllProjectionsAndIsIdempotentOnRepeat() {
		UUID aliceWallet = walletCommands.openWallet(UUID.randomUUID(), Currency.VND);
		UUID bobWallet = walletCommands.openWallet(UUID.randomUUID(), Currency.VND);
		depositCommands.deposit(UUID.randomUUID(), aliceWallet, Money.vnd(1_000_000),
				"REBUILD-DEP-" + UUID.randomUUID());
		transferOrchestrator.transfer(UUID.randomUUID(), aliceWallet, bobWallet, Money.vnd(350_000));

		ProjectionRebuildService.RebuildResult first = rebuildService.rebuildAll();
		assertThat(first.eventsReplayed()).isGreaterThan(0);

		long aliceBalanceFirst = queryRepository.findBalance(aliceWallet).orElseThrow().balance();
		long bobBalanceFirst = queryRepository.findBalance(bobWallet).orElseThrow().balance();
		long clearingFirst = ledgerQueryRepository.accountNetCreditMinusDebit("TRANSFER_CLEARING");
		assertThat(aliceBalanceFirst).isEqualTo(650_000L);
		assertThat(bobBalanceFirst).isEqualTo(350_000L);
		assertThat(ledgerQueryRepository.totalDebitMinusCredit()).isZero();

		ProjectionRebuildService.RebuildResult second = rebuildService.rebuildAll();
		assertThat(second.eventsReplayed()).isEqualTo(first.eventsReplayed());
		assertThat(queryRepository.findBalance(aliceWallet).orElseThrow().balance()).isEqualTo(aliceBalanceFirst);
		assertThat(queryRepository.findBalance(bobWallet).orElseThrow().balance()).isEqualTo(bobBalanceFirst);
		assertThat(ledgerQueryRepository.accountNetCreditMinusDebit("TRANSFER_CLEARING")).isEqualTo(clearingFirst);
		assertThat(queryRepository.findTransactions(aliceWallet, null, 20)).hasSize(2);
		assertThat(queryRepository.findTransactions(bobWallet, null, 20)).hasSize(1);
	}

	@Test
	void rebuildReplaysHistorical150CharDepositRequestedAndFailedEventsCleanly() {
		UUID legacyWallet = walletCommands.openWallet(UUID.randomUUID(), Currency.VND);
		UUID legacyDepositId = UUID.randomUUID();
		String legacyPrefix = "LEGACY-" + UUID.randomUUID() + "-";
		String legacy150Ref = legacyPrefix + "X".repeat(150 - legacyPrefix.length());
		assertThat(legacy150Ref).hasSize(150);

		OffsetDateTime historicalTs = OffsetDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
		DepositRequested requested = new DepositRequested(legacyDepositId, legacyWallet, Money.vnd(125_000),
				legacy150Ref);
		DepositFailed failed = new DepositFailed(legacyDepositId, "legacy bank rejection");

		String insertSql = """
				INSERT INTO events (event_id, aggregate_id, aggregate_type, version, event_type, schema_version, occurred_at, correlation_id, causation_id, payload)
				VALUES (?, ?, 'Deposit', ?, ?, 1, ?, ?, NULL, ?::jsonb)
				""";
		jdbc.update(insertSql, UUID.randomUUID(), legacyDepositId, 1L, requested.eventType(),
				historicalTs, legacyDepositId, serializer.toJson(requested));
		jdbc.update(insertSql, UUID.randomUUID(), legacyDepositId, 2L, failed.eventType(),
				historicalTs.plusSeconds(5), legacyDepositId, serializer.toJson(failed));

		rebuildService.rebuildAll();

		var rows = queryRepository.findTransactions(legacyWallet, null, 10);
		assertThat(rows).hasSize(1);
		assertThat(rows.getFirst().transactionId()).isEqualTo(legacyDepositId);
		assertThat(rows.getFirst().status()).isEqualTo("FAILED");
		assertThat(rows.getFirst().bankReference()).isEqualTo(legacy150Ref);
	}

	@Test
	void rebuildRollsBackAtomicallyWhenMidReplayFails() {
		UUID walletId = walletCommands.openWallet(UUID.randomUUID(), Currency.VND);
		depositCommands.deposit(UUID.randomUUID(), walletId, Money.vnd(750_000),
				"ATOMIC-ROLLBACK-" + UUID.randomUUID());

		rebuildService.rebuildAll();

		long balanceBefore = queryRepository.findBalance(walletId).orElseThrow().balance();
		long ledgerCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class);
		long txHistoryCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM transaction_history", Long.class);
		long dailyReportCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM daily_reports", Long.class);
		long processedCountBefore = jdbc.queryForObject("SELECT COUNT(*) FROM processed_events", Long.class);
		assertThat(balanceBefore).isEqualTo(750_000L);
		assertThat(ledgerCountBefore).isGreaterThan(0L);
		assertThat(txHistoryCountBefore).isGreaterThan(0L);
		assertThat(dailyReportCountBefore).isGreaterThan(0L);
		assertThat(processedCountBefore).isGreaterThan(0L);

		AtomicInteger calls = new AtomicInteger(0);
		BalanceProjector failingProjector = new BalanceProjector(jdbc, tx, balanceProjector.consumerGroup()) {
			@Override
			public boolean project(UUID eventId, DomainEvent event, long aggregateVersion, Instant occurredAt) {
				if (calls.incrementAndGet() == 2) {
					throw new IllegalStateException("mid-replay boom");
				}
				return super.project(eventId, event, aggregateVersion, occurredAt);
			}
		};

		ProjectionRebuildService failingService = new ProjectionRebuildService(eventStore, jdbc, tx, ledgerProjector,
				failingProjector, txHistoryProjector, listenerRegistryProvider, clock, 1);

		assertThatThrownBy(failingService::rebuildAll)
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("mid-replay boom");

		assertThat(queryRepository.findBalance(walletId).orElseThrow().balance()).isEqualTo(balanceBefore);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class)).isEqualTo(ledgerCountBefore);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_history", Long.class))
				.isEqualTo(txHistoryCountBefore);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM daily_reports", Long.class))
				.isEqualTo(dailyReportCountBefore);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events", Long.class))
				.isEqualTo(processedCountBefore);
	}

	@Test
	void rebuildUsesRepeatableReadSnapshotPreservesPreRebuildVisibilityAndPreventsKafkaDoubleCount() {
		UUID walletId = walletCommands.openWallet(UUID.randomUUID(), Currency.VND);
		UUID depositId = UUID.randomUUID();
		depositCommands.deposit(depositId, walletId, Money.vnd(1_000_000),
				"SNAP-DEP-" + UUID.randomUUID());

		rebuildService.rebuildAll();

		List<GlobalStoredEvent> snapshotEvents = eventStore.loadBatch(0L, 10_000);
		GlobalStoredEvent walletCreditEvent = snapshotEvents.stream()
				.filter(e -> e.storedEvent().aggregateId().equals(walletId)
						&& "WalletCredited".equals(e.storedEvent().eventType()))
				.findFirst()
				.orElseThrow();
		GlobalStoredEvent depositConfirmedEvent = snapshotEvents.stream()
				.filter(e -> e.storedEvent().aggregateId().equals(depositId)
						&& "DepositConfirmed".equals(e.storedEvent().eventType()))
				.findFirst()
				.orElseThrow();

		UUID postSnapshotDepositId = UUID.randomUUID();
		UUID postSnapshotReqEventId = UUID.randomUUID();
		UUID postSnapshotConfEventId = UUID.randomUUID();
		AtomicBoolean callbackRan = new AtomicBoolean(false);
		AtomicReference<String> observedIsolation = new AtomicReference<>();
		AtomicReference<Long> midRebuildVisibleWalletCount = new AtomicReference<>();

		BalanceProjector spyProjector = new BalanceProjector(jdbc, tx, balanceProjector.consumerGroup()) {
			@Override
			public boolean project(UUID eventId, DomainEvent event, long aggregateVersion, Instant occurredAt) {
				boolean applied = super.project(eventId, event, aggregateVersion, occurredAt);
				if (callbackRan.compareAndSet(false, true)) {
					observedIsolation.set(
							jdbc.queryForObject("SELECT current_setting('transaction_isolation')", String.class));
					try (Connection externalConn = dataSource.getConnection()) {
						externalConn.setAutoCommit(true);
						try (PreparedStatement ps = externalConn.prepareStatement("SELECT COUNT(*) FROM wallet_balances");
								ResultSet rs = ps.executeQuery()) {
							rs.next();
							midRebuildVisibleWalletCount.set(rs.getLong(1));
						}
						DepositRequested postReq = new DepositRequested(postSnapshotDepositId, walletId,
								Money.vnd(200_000), "POST-SNAP-" + postSnapshotDepositId);
						DepositConfirmed postConf = new DepositConfirmed(postSnapshotDepositId);
						String insertSql = """
								INSERT INTO events (event_id, aggregate_id, aggregate_type, version, event_type, schema_version, occurred_at, correlation_id, causation_id, payload)
								VALUES (?, ?, 'Deposit', ?, ?, 1, now(), ?, NULL, ?::jsonb)
								""";
						try (PreparedStatement ps = externalConn.prepareStatement(insertSql)) {
							ps.setObject(1, postSnapshotReqEventId);
							ps.setObject(2, postSnapshotDepositId);
							ps.setLong(3, 1L);
							ps.setString(4, postReq.eventType());
							ps.setObject(5, postSnapshotDepositId);
							ps.setString(6, serializer.toJson(postReq));
							ps.executeUpdate();
						}
						try (PreparedStatement ps = externalConn.prepareStatement(insertSql)) {
							ps.setObject(1, postSnapshotConfEventId);
							ps.setObject(2, postSnapshotDepositId);
							ps.setLong(3, 2L);
							ps.setString(4, postConf.eventType());
							ps.setObject(5, postSnapshotDepositId);
							ps.setString(6, serializer.toJson(postConf));
							ps.executeUpdate();
						}
					} catch (Exception ex) {
						throw new RuntimeException(ex);
					}
				}
				return applied;
			}
		};

		ProjectionRebuildService customService = new ProjectionRebuildService(eventStore, jdbc, tx, ledgerProjector,
				spyProjector, txHistoryProjector, listenerRegistryProvider, clock, 1);

		customService.rebuildAll();

		assertThat(callbackRan.get()).isTrue();
		assertThat(observedIsolation.get()).isEqualTo("repeatable read");
		assertThat(midRebuildVisibleWalletCount.get()).isGreaterThan(0L);

		Integer postReqProcessed = jdbc.queryForObject(
				"SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Integer.class, postSnapshotReqEventId);
		Integer postConfProcessed = jdbc.queryForObject(
				"SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Integer.class, postSnapshotConfEventId);
		assertThat(postReqProcessed).isZero();
		assertThat(postConfProcessed).isZero();

		long balanceAfterRebuild = queryRepository.findBalance(walletId).orElseThrow().balance();
		long ledgerCountAfterRebuild = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class);
		LocalDate reportDate = depositConfirmedEvent.storedEvent().occurredAt()
				.atZone(TransactionHistoryProjector.REPORT_ZONE).toLocalDate();
		QueryRepository.DailyReportRow reportAfterRebuild = queryRepository
				.findDailyReports(reportDate, reportDate).stream()
				.filter(r -> "VND".equals(r.currency()))
				.findFirst()
				.orElseThrow();

		// Reverse direction: Kafka redelivering snapshot-T0 events after restart must be a no-op.
		boolean ledgerRedelivered = ledgerProjector.project(
				walletCreditEvent.storedEvent().eventId(),
				walletCreditEvent.storedEvent().event(),
				walletCreditEvent.storedEvent().occurredAt());
		boolean balanceRedelivered = balanceProjector.project(
				walletCreditEvent.storedEvent().eventId(),
				walletCreditEvent.storedEvent().event(),
				walletCreditEvent.storedEvent().version(),
				walletCreditEvent.storedEvent().occurredAt());
		boolean txHistoryRedelivered = txHistoryProjector.project(
				depositConfirmedEvent.storedEvent().eventId(),
				depositConfirmedEvent.storedEvent().event(),
				depositConfirmedEvent.storedEvent().occurredAt());

		assertThat(ledgerRedelivered).isFalse();
		assertThat(balanceRedelivered).isFalse();
		assertThat(txHistoryRedelivered).isFalse();
		assertThat(queryRepository.findBalance(walletId).orElseThrow().balance()).isEqualTo(balanceAfterRebuild);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class))
				.isEqualTo(ledgerCountAfterRebuild);
		QueryRepository.DailyReportRow reportAfterRedeliver = queryRepository
				.findDailyReports(reportDate, reportDate).stream()
				.filter(r -> "VND".equals(r.currency()))
				.findFirst()
				.orElseThrow();
		assertThat(reportAfterRedeliver.totalDeposit()).isEqualTo(reportAfterRebuild.totalDeposit());
		assertThat(reportAfterRedeliver.transactionCount()).isEqualTo(reportAfterRebuild.transactionCount());
	}
}
