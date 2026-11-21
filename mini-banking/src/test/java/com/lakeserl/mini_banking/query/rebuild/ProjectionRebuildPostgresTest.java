package com.lakeserl.mini_banking.query.rebuild;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.deposit.command.DepositCommandHandler;
import com.lakeserl.mini_banking.ledger.projection.LedgerQueryRepository;
import com.lakeserl.mini_banking.query.api.QueryRepository;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
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

	@BeforeEach
	void clean() {
		jdbc.update("TRUNCATE TABLE ledger_entries, wallet_balances, transaction_history, daily_reports, processed_events, transfer_saga_state RESTART IDENTITY");
	}

	@Test
	void rebuildFromEventStoreMatchesAllProjectionsAndIsIdempotentOnRepeat() {
		UUID aliceWallet = walletCommands.openWallet(UUID.randomUUID(), Currency.VND);
		UUID bobWallet = walletCommands.openWallet(UUID.randomUUID(), Currency.VND);
		depositCommands.deposit(UUID.randomUUID(), aliceWallet, Money.vnd(1_000_000), "REBUILD-DEP-1");
		transferOrchestrator.transfer(UUID.randomUUID(), aliceWallet, bobWallet, Money.vnd(350_000));

		ProjectionRebuildService.RebuildResult first = rebuildService.rebuildAll();
		assertThat(first.eventsReplayed()).isGreaterThan(0);

		long aliceBalanceFirst = queryRepository.findBalance(aliceWallet).orElseThrow().balance();
		long bobBalanceFirst = queryRepository.findBalance(bobWallet).orElseThrow().balance();
		assertThat(aliceBalanceFirst).isEqualTo(650_000L);
		assertThat(bobBalanceFirst).isEqualTo(350_000L);
		assertThat(ledgerQueryRepository.totalDebitMinusCredit()).isZero();
		assertThat(ledgerQueryRepository.accountNetCreditMinusDebit("TRANSFER_CLEARING")).isZero();

		ProjectionRebuildService.RebuildResult second = rebuildService.rebuildAll();
		assertThat(second.eventsReplayed()).isEqualTo(first.eventsReplayed());
		assertThat(queryRepository.findBalance(aliceWallet).orElseThrow().balance()).isEqualTo(aliceBalanceFirst);
		assertThat(queryRepository.findBalance(bobWallet).orElseThrow().balance()).isEqualTo(bobBalanceFirst);
		assertThat(queryRepository.findTransactions(aliceWallet, null, 20)).hasSize(2);
		assertThat(queryRepository.findTransactions(bobWallet, null, 20)).hasSize(1);
	}
}
