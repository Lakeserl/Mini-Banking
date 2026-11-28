package com.lakeserl.mini_banking.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.deposit.command.DepositCommandHandler;
import com.lakeserl.mini_banking.mockbank.InMemoryMockBankGateway;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.BankStatementEntry;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.PayoutStatus;
import com.lakeserl.mini_banking.query.rebuild.ProjectionRebuildService;
import com.lakeserl.mini_banking.reconciliation.ReconciliationIssueRepository.ReconciliationIssue;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;
import com.lakeserl.mini_banking.withdrawal.command.WithdrawalCommandHandler;
import com.lakeserl.mini_banking.withdrawal.domain.WithdrawalStatus;
import com.lakeserl.mini_banking.withdrawal.infrastructure.WithdrawalRepository;

@PostgresDataTest
class ReconciliationPostgresTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	WalletCommandHandler walletCommands;

	@Autowired
	WalletRepository walletRepository;

	@Autowired
	DepositCommandHandler depositCommands;

	@Autowired
	WithdrawalCommandHandler withdrawalCommands;

	@Autowired
	WithdrawalRepository withdrawalRepository;

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

	@Test
	void pendingWithdrawalLifecycleRespects5mSilence24hEscalationResolvedDedupeAndAutoResolve() {
		UUID walletId = walletCommands.openWallet(UUID.randomUUID(), Currency.VND);
		depositCommands.deposit(UUID.randomUUID(), walletId, Money.vnd(1_000_000), "RECON-WDR-SEED-" + UUID.randomUUID());
		UUID withdrawalId = UUID.randomUUID();
		Money amount = Money.vnd(300_000);

		mockBank.presetPayout(withdrawalId, PayoutStatus.TIMEOUT);
		assertThat(withdrawalCommands.withdraw(withdrawalId, walletId, amount, "97043612345678"))
				.isEqualTo(WithdrawalStatus.PENDING);
		rebuildService.rebuildAll();

		String dedupeKey = "BANK_MISMATCH:PENDING_WITHDRAWAL:" + withdrawalId;
		LocalDate todayVn = Instant.now().atZone(BankReconciliationService.BANK_ZONE).toLocalDate();

		// Age transaction to 10 minutes (> 300s settle threshold, < 24h escalation threshold)
		backdateTransactionHistory(withdrawalId, Instant.now().minus(Duration.ofMinutes(10)));

		// Invariant 1: 5-minute run on TIMEOUT creates 0 issues
		bankReconciliation.reconcileStalePendingTransactions();
		assertThat(findIssuesByDedupeKey(dedupeKey)).isEmpty();

		// Daily run while younger than 24h escalation threshold also creates 0 issues
		bankReconciliation.reconcileDate(todayVn);
		assertThat(findIssuesByDedupeKey(dedupeKey)).isEmpty();

		// Age transaction past 24h escalation threshold (25h)
		backdateTransactionHistory(withdrawalId, Instant.now().minus(Duration.ofHours(25)));

		// Invariant 2: daily run creates exactly 1 issue; running twice consecutively stays 1
		bankReconciliation.reconcileDate(todayVn);
		bankReconciliation.reconcileDate(todayVn);
		List<ReconciliationIssue> afterDaily = findIssuesByDedupeKey(dedupeKey);
		assertThat(afterDaily).hasSize(1);
		assertThat(afterDaily.getFirst().status()).isEqualTo(ReconciliationIssueStatus.OPEN);

		// Invariant 3a: Ops resolves -> re-running BOTH 5m and daily crons creates 0 new issues
		issueRepository.resolve(afterDaily.getFirst().id(), "ops-1", "Waiting on bank batch", Instant.now());
		bankReconciliation.reconcileStalePendingTransactions();
		bankReconciliation.reconcileDate(todayVn);
		assertThat(findIssuesByDedupeKey(dedupeKey)).hasSize(1);
		assertThat(findIssuesByDedupeKey(dedupeKey).getFirst().status()).isEqualTo(ReconciliationIssueStatus.RESOLVED);

		// Invariant 3b: Bank data changes (amount mismatch) -> new OPEN issue IS created
		mockBank.recordPayoutTransaction(withdrawalId, Money.vnd(999_000), PayoutStatus.SUCCESS, Instant.now());
		bankReconciliation.reconcileStalePendingTransactions();
		List<ReconciliationIssue> afterMismatch = findIssuesByDedupeKey(dedupeKey);
		assertThat(afterMismatch).hasSize(2);
		assertThat(afterMismatch.getLast().status()).isEqualTo(ReconciliationIssueStatus.OPEN);

		// Resolve the MISMATCH -> re-running 5m cron does NOT reopen when bank data unchanged
		issueRepository.resolve(afterMismatch.getLast().id(), "ops-1", "Escalated to bank", Instant.now());
		bankReconciliation.reconcileStalePendingTransactions();
		assertThat(findIssuesByDedupeKey(dedupeKey)).hasSize(2);

		// Change bank mismatch amount again -> new OPEN issue created, then bank corrects to REJECTED
		mockBank.recordPayoutTransaction(withdrawalId, Money.vnd(888_000), PayoutStatus.SUCCESS, Instant.now());
		bankReconciliation.reconcileStalePendingTransactions();
		List<ReconciliationIssue> beforeSettle = findIssuesByDedupeKey(dedupeKey);
		assertThat(beforeSettle).hasSize(3);
		assertThat(beforeSettle.getLast().status()).isEqualTo(ReconciliationIssueStatus.OPEN);

		// Invariant 4: Bank settles cleanly to REJECTED -> 5m run settles, compensates wallet, and AUTO-RESOLVES OPEN issue
		mockBank.recordPayoutTransaction(withdrawalId, amount, PayoutStatus.REJECTED, Instant.now());
		bankReconciliation.reconcileStalePendingTransactions();

		assertThat(withdrawalRepository.load(withdrawalId)).get().extracting(w -> w.status())
				.isEqualTo(WithdrawalStatus.FAILED);
		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance())
				.isEqualTo(Money.vnd(1_000_000));
		List<ReconciliationIssue> afterSettle = findIssuesByDedupeKey(dedupeKey);
		assertThat(afterSettle).hasSize(3);
		assertThat(afterSettle).allSatisfy(issue ->
				assertThat(issue.status()).isEqualTo(ReconciliationIssueStatus.RESOLVED));
		assertThat(afterSettle.getLast().resolvedBy()).isEqualTo(BankReconciliationService.AUTO_RESOLVED_BY);
	}

	private void backdateTransactionHistory(UUID transactionId, Instant ts) {
		OffsetDateTime odt = OffsetDateTime.ofInstant(ts, ZoneOffset.UTC);
		jdbc.update("UPDATE transaction_history SET occurred_at = ?, updated_at = ? WHERE transaction_id = ?",
				odt, odt, transactionId);
	}

	private List<ReconciliationIssue> findIssuesByDedupeKey(String dedupeKey) {
		return jdbc.query("""
				SELECT id, issue_type, dedupe_key, details::text AS details, status, detected_at,
				       resolved_at, resolved_by, resolution_notes
				FROM reconciliation_issues
				WHERE dedupe_key = ?
				ORDER BY id ASC
				""",
				(rs, i) -> {
					OffsetDateTime resolvedTs = rs.getObject("resolved_at", OffsetDateTime.class);
					return new ReconciliationIssue(
							rs.getLong("id"),
							ReconciliationIssueType.valueOf(rs.getString("issue_type")),
							rs.getString("dedupe_key"),
							rs.getString("details"),
							ReconciliationIssueStatus.valueOf(rs.getString("status")),
							rs.getObject("detected_at", OffsetDateTime.class).toInstant(),
							resolvedTs != null ? resolvedTs.toInstant() : null,
							rs.getString("resolved_by"),
							rs.getString("resolution_notes"));
				},
				dedupeKey);
	}
}
