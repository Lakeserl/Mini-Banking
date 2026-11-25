package com.lakeserl.mini_banking.query.rebuild;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.deposit.command.DepositCommandHandler;
import com.lakeserl.mini_banking.deposit.domain.events.DepositFailed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositRequested;
import com.lakeserl.mini_banking.ledger.projection.LedgerQueryRepository;
import com.lakeserl.mini_banking.query.api.QueryRepository;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.EventSerializer;
import com.lakeserl.mini_banking.transfer.saga.TransferSagaOrchestrator;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;

@PostgresDataTest
class ProjectionRebuildPostgresTest {

	@Autowired
	JdbcTemplate jdbc;

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
}
