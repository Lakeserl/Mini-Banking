package com.lakeserl.mini_banking.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.deposit.command.DepositCommandHandler;
import com.lakeserl.mini_banking.mockbank.InMemoryMockBankGateway;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.BankStatementEntry;
import com.lakeserl.mini_banking.query.rebuild.ProjectionRebuildService;
import com.lakeserl.mini_banking.reconciliation.ReconciliationIssueRepository.ReconciliationIssue;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;

@PostgresDataTest
class ReconciliationPostgresTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	WalletCommandHandler walletCommands;

	@Autowired
	DepositCommandHandler depositCommands;

	@Autowired
	ProjectionRebuildService rebuildService;

	@Autowired
	InternalReconciliationService internalReconciliation;

	@Autowired
	BankReconciliationService bankReconciliation;

	@Autowired
	ReconciliationIssueRepository issueRepository;

	@Autowired
	InMemoryMockBankGateway mockBank;

	@BeforeEach
	void clean() {
		mockBank.reset();
		jdbc.update("TRUNCATE TABLE reconciliation_issues, transfer_saga_state RESTART IDENTITY");
	}

	@Test
	void internalAndBankReconciliationDetectMismatchesIdempotentlyAndAllowAdminResolve() {
		UUID walletId = walletCommands.openWallet(UUID.randomUUID(), Currency.VND);
		depositCommands.deposit(UUID.randomUUID(), walletId, Money.vnd(500_000), "RECON-DEP-" + UUID.randomUUID());
		rebuildService.rebuildAll();

		// Run once to establish baseline against any shared-container events from earlier tests
		internalReconciliation.run();
		assertThat(issueRepository.findByStatus(ReconciliationIssueStatus.OPEN, null, 100))
				.noneMatch(i -> i.dedupeKey().equals("BALANCE_MISMATCH:" + walletId));

		// Tamper wallet_balances directly to simulate corrupted projection without touching events
		jdbc.update("UPDATE wallet_balances SET balance = balance - 1000 WHERE wallet_id = ?", walletId);
		assertThat(internalReconciliation.run().issuesOpened()).isEqualTo(1);
		// Second run must be idempotent (no duplicate OPEN issue)
		assertThat(internalReconciliation.run().issuesOpened()).isZero();

		// Balance was NOT auto-mutated by reconciliation
		Long stillCorrupted = jdbc.queryForObject("SELECT balance FROM wallet_balances WHERE wallet_id = ?",
				Long.class, walletId);
		assertThat(stillCorrupted).isEqualTo(499_000L);

		// Inject an extra bank statement entry for today in Asia/Ho_Chi_Minh
		Instant now = Instant.now();
		LocalDate todayVn = now.atZone(BankReconciliationService.BANK_ZONE).toLocalDate();
		mockBank.addStatementEntry(new BankStatementEntry(
				"BANK-GHOST-" + UUID.randomUUID(), "DEPOSIT", UUID.randomUUID(), Money.vnd(777_000), "SUCCESS", now));
		assertThat(bankReconciliation.reconcileDate(todayVn).issuesOpened()).isGreaterThanOrEqualTo(1);

		var openIssues = issueRepository.findByStatus(ReconciliationIssueStatus.OPEN, null, 20);
		assertThat(openIssues).hasSizeGreaterThanOrEqualTo(2);

		ReconciliationIssue resolved = issueRepository.resolve(openIssues.getFirst().id(), "admin-uuid",
				"Investigated and rebuilt projection", Instant.now());
		assertThat(resolved.status()).isEqualTo(ReconciliationIssueStatus.RESOLVED);
		assertThat(resolved.resolvedBy()).isEqualTo("admin-uuid");
	}
}
