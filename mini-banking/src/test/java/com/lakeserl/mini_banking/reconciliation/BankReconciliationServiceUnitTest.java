package com.lakeserl.mini_banking.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lakeserl.mini_banking.deposit.command.DepositCommandHandler;
import com.lakeserl.mini_banking.deposit.domain.DepositStatus;
import com.lakeserl.mini_banking.mockbank.MockBankGateway;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.BankStatementEntry;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.BankTransaction;
import com.lakeserl.mini_banking.query.api.QueryRepository;
import com.lakeserl.mini_banking.query.api.QueryRepository.TransactionHistoryRow;
import com.lakeserl.mini_banking.reconciliation.InternalReconciliationService.ReconciliationRunResult;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.withdrawal.command.WithdrawalCommandHandler;
import com.lakeserl.mini_banking.withdrawal.command.WithdrawalCommandHandler.ReconcileOutcome;

class BankReconciliationServiceUnitTest {

	private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
	private static final LocalDate REPORT_DATE = LocalDate.of(2026, 11, 21);

	private MockBankGateway mockBankGateway;
	private QueryRepository queryRepository;
	private DepositCommandHandler depositCommandHandler;
	private WithdrawalCommandHandler withdrawalCommandHandler;
	private ReconciliationIssueRepository issueRepository;
	private BankReconciliationService service;

	@BeforeEach
	void setUp() {
		mockBankGateway = mock(MockBankGateway.class);
		queryRepository = mock(QueryRepository.class);
		depositCommandHandler = mock(DepositCommandHandler.class);
		withdrawalCommandHandler = mock(WithdrawalCommandHandler.class);
		issueRepository = mock(ReconciliationIssueRepository.class);
		when(queryRepository.findStalePendingDeposits(any())).thenReturn(List.of());
		when(queryRepository.findStalePendingWithdrawals(any())).thenReturn(List.of());
		when(queryRepository.findFailedWithdrawalsBetween(any(), any())).thenReturn(List.of());
		when(mockBankGateway.findPayout(any())).thenReturn(Optional.empty());
		service = new BankReconciliationService(mockBankGateway, queryRepository, depositCommandHandler,
				withdrawalCommandHandler, issueRepository, Clock.fixed(NOW, ZoneOffset.UTC), false,
				300L, 86_400L, 7);
	}

	@Test
	void constructorRejectsInvalidThresholdsAndLookback() {
		assertThatThrownBy(() -> new BankReconciliationService(mockBankGateway, queryRepository, depositCommandHandler,
				withdrawalCommandHandler, issueRepository, Clock.fixed(NOW, ZoneOffset.UTC), false, 300L, 86_400L, 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new BankReconciliationService(mockBankGateway, queryRepository, depositCommandHandler,
				withdrawalCommandHandler, issueRepository, Clock.fixed(NOW, ZoneOffset.UTC), false, -1L, 86_400L, 7))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new BankReconciliationService(mockBankGateway, queryRepository, depositCommandHandler,
				withdrawalCommandHandler, issueRepository, Clock.fixed(NOW, ZoneOffset.UTC), false, 300L, -1L, 7))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void matchingBankStatementAndSystemTransactionsOpensZeroIssues() {
		UUID depId = UUID.randomUUID();
		UUID wdrId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Instant txTime = Instant.parse("2026-11-21T03:00:00Z");

		when(mockBankGateway.getDailyStatement(REPORT_DATE, BankReconciliationService.BANK_ZONE))
				.thenReturn(List.of(
						new BankStatementEntry("DEP-1", "DEPOSIT", depId, Money.vnd(500_000), "SUCCESS", txTime),
						new BankStatementEntry("PAY-1", "PAYOUT", wdrId, Money.vnd(200_000), "SUCCESS", txTime)));
		when(queryRepository.findSettledBankTransactions(any(), any()))
				.thenReturn(List.of(
						txRow(depId, walletId, "DEPOSIT", 500_000L, "CONFIRMED", "DEP-1", txTime),
						txRow(wdrId, walletId, "WITHDRAWAL", 200_000L, "CONFIRMED", "PAY-1", txTime)));

		ReconciliationRunResult result = service.reconcileDate(REPORT_DATE);

		assertThat(result.runType()).isEqualTo("BANK");
		assertThat(result.issuesOpened()).isZero();
		verify(issueRepository, never()).openIfAbsent(any(), anyString(), anyString(), any());
	}

	@Test
	void detectsDuplicateExtraMissingAndAmountMismatchInBankStatement() {
		UUID depMatch = UUID.randomUUID();
		UUID depMismatch = UUID.randomUUID();
		UUID depMissingInBank = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Instant txTime = Instant.parse("2026-11-21T04:00:00Z");

		when(mockBankGateway.getDailyStatement(REPORT_DATE, BankReconciliationService.BANK_ZONE))
				.thenReturn(List.of(
						new BankStatementEntry("DUP-REF", "DEPOSIT", depMatch, Money.vnd(100_000), "SUCCESS", txTime),
						new BankStatementEntry("DUP-REF", "DEPOSIT", depMatch, Money.vnd(100_000), "SUCCESS", txTime),
						new BankStatementEntry("EXTRA-REF", "DEPOSIT", UUID.randomUUID(), Money.vnd(300_000), "SUCCESS", txTime),
						new BankStatementEntry("MISMATCH-REF", "DEPOSIT", depMismatch, Money.vnd(999_000), "SUCCESS", txTime)));
		when(queryRepository.findSettledBankTransactions(any(), any()))
				.thenReturn(List.of(
						txRow(depMatch, walletId, "DEPOSIT", 100_000L, "CONFIRMED", "DUP-REF", txTime),
						txRow(depMismatch, walletId, "DEPOSIT", 500_000L, "CONFIRMED", "MISMATCH-REF", txTime),
						txRow(depMissingInBank, walletId, "DEPOSIT", 400_000L, "CONFIRMED", "SYS-ONLY-REF", txTime)));
		when(issueRepository.openIfAbsent(any(), anyString(), anyString(), eq(NOW))).thenReturn(true);

		ReconciliationRunResult result = service.reconcileDate(REPORT_DATE);

		assertThat(result.issuesOpened()).isEqualTo(4);
		verify(issueRepository).openIfAbsent(eq(ReconciliationIssueType.BANK_MISMATCH),
				eq("BANK_MISMATCH:DUPLICATE:2026-11-21:DUP-REF"), anyString(), eq(NOW));
		verify(issueRepository).openIfAbsent(eq(ReconciliationIssueType.BANK_MISMATCH),
				eq("BANK_MISMATCH:EXTRA_IN_BANK:2026-11-21:EXTRA-REF"), anyString(), eq(NOW));
		verify(issueRepository).openIfAbsent(eq(ReconciliationIssueType.BANK_MISMATCH),
				eq("BANK_MISMATCH:AMOUNT_MISMATCH:2026-11-21:MISMATCH-REF"), anyString(), eq(NOW));
		verify(issueRepository).openIfAbsent(eq(ReconciliationIssueType.BANK_MISMATCH),
				eq("BANK_MISMATCH:MISSING_IN_BANK:2026-11-21:SYS-ONLY-REF"), anyString(), eq(NOW));
	}

	@Test
	void stalePendingDepositsEscalateOnlyPast24hAndAutoResolveWhenSettled() {
		UUID resolvedDep = UUID.randomUUID();
		UUID youngUnresolvedDep = UUID.randomUUID();
		UUID oldUnresolvedDep = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Instant youngTime = NOW.minusSeconds(600);
		Instant oldTime = NOW.minusSeconds(90_000);

		when(mockBankGateway.getDailyStatement(REPORT_DATE, BankReconciliationService.BANK_ZONE))
				.thenReturn(List.of());
		when(queryRepository.findSettledBankTransactions(any(), any())).thenReturn(List.of());
		when(queryRepository.findStalePendingDeposits(NOW.minusSeconds(300)))
				.thenReturn(List.of(
						txRow(resolvedDep, walletId, "DEPOSIT", 200_000L, "PENDING", "PEND-OK", oldTime),
						txRow(youngUnresolvedDep, walletId, "DEPOSIT", 250_000L, "PENDING", "PEND-YOUNG", youngTime),
						txRow(oldUnresolvedDep, walletId, "DEPOSIT", 300_000L, "PENDING", "PEND-OLD", oldTime)));
		when(mockBankGateway.findTransaction("PEND-OK")).thenReturn(Optional.of(
				new BankTransaction("PEND-OK", "DEPOSIT", resolvedDep, Money.vnd(200_000), "SUCCESS", null, oldTime)));
		when(mockBankGateway.findTransaction("PEND-YOUNG")).thenReturn(Optional.of(
				new BankTransaction("PEND-YOUNG", "DEPOSIT", youngUnresolvedDep, Money.vnd(250_000), "TIMEOUT", "timeout", youngTime)));
		when(mockBankGateway.findTransaction("PEND-OLD")).thenReturn(Optional.of(
				new BankTransaction("PEND-OLD", "DEPOSIT", oldUnresolvedDep, Money.vnd(300_000), "TIMEOUT", "timeout", oldTime)));
		when(depositCommandHandler.deposit(resolvedDep, walletId, Money.vnd(200_000), "PEND-OK"))
				.thenReturn(DepositStatus.CONFIRMED);
		when(issueRepository.openIfAbsent(any(), anyString(), anyString(), eq(NOW))).thenReturn(true);

		ReconciliationRunResult result = service.reconcileDate(REPORT_DATE);

		assertThat(result.issuesOpened()).isEqualTo(1);
		verify(depositCommandHandler).deposit(resolvedDep, walletId, Money.vnd(200_000), "PEND-OK");
		verify(issueRepository).resolveOpenByDedupeKey(eq("BANK_MISMATCH:PENDING_DEPOSIT:" + resolvedDep),
				eq(BankReconciliationService.AUTO_RESOLVED_BY), anyString(), eq(NOW));
		verify(issueRepository, never()).openIfAbsent(any(), eq("BANK_MISMATCH:PENDING_DEPOSIT:" + youngUnresolvedDep),
				anyString(), any());
		verify(issueRepository).openIfAbsent(eq(ReconciliationIssueType.BANK_MISMATCH),
				eq("BANK_MISMATCH:PENDING_DEPOSIT:" + oldUnresolvedDep), anyString(), eq(NOW));
	}

	@Test
	void fiveMinutePendingRunDoesNotOpenIssuesForUnresolvedTimeoutsButSettlesAndAutoResolves() {
		UUID settledDep = UUID.randomUUID();
		UUID timeoutDep = UUID.randomUUID();
		UUID settledWdr = UUID.randomUUID();
		UUID timeoutWdr = UUID.randomUUID();
		UUID mismatchWdr = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Instant veryOld = NOW.minusSeconds(100_000);

		when(queryRepository.findStalePendingDeposits(NOW.minusSeconds(300)))
				.thenReturn(List.of(
						txRow(settledDep, walletId, "DEPOSIT", 100_000L, "PENDING", "DEP-SETTLED", veryOld),
						txRow(timeoutDep, walletId, "DEPOSIT", 120_000L, "PENDING", "DEP-TIMEOUT", veryOld)));
		when(mockBankGateway.findTransaction("DEP-SETTLED")).thenReturn(Optional.of(
				new BankTransaction("DEP-SETTLED", "DEPOSIT", settledDep, Money.vnd(100_000), "SUCCESS", null, veryOld)));
		when(mockBankGateway.findTransaction("DEP-TIMEOUT")).thenReturn(Optional.of(
				new BankTransaction("DEP-TIMEOUT", "DEPOSIT", timeoutDep, Money.vnd(120_000), "TIMEOUT", "timeout", veryOld)));
		when(depositCommandHandler.deposit(settledDep, walletId, Money.vnd(100_000), "DEP-SETTLED"))
				.thenReturn(DepositStatus.CONFIRMED);

		when(queryRepository.findStalePendingWithdrawals(NOW.minusSeconds(300)))
				.thenReturn(List.of(
						txRow(settledWdr, walletId, "WITHDRAWAL", 200_000L, "PENDING", null, veryOld),
						txRow(timeoutWdr, walletId, "WITHDRAWAL", 220_000L, "PENDING", null, veryOld),
						txRow(mismatchWdr, walletId, "WITHDRAWAL", 250_000L, "PENDING", null, veryOld)));
		when(withdrawalCommandHandler.reconcilePendingWithdrawal(settledWdr)).thenReturn(ReconcileOutcome.SETTLED);
		when(withdrawalCommandHandler.reconcilePendingWithdrawal(timeoutWdr)).thenReturn(ReconcileOutcome.UNRESOLVED);
		when(withdrawalCommandHandler.reconcilePendingWithdrawal(mismatchWdr)).thenReturn(ReconcileOutcome.MISMATCH);
		when(issueRepository.openIfAbsent(any(), anyString(), anyString(), eq(NOW))).thenReturn(true);

		ReconciliationRunResult result = service.reconcileStalePendingTransactions();

		assertThat(result.runType()).isEqualTo("BANK_PENDING");
		assertThat(result.issuesOpened()).isEqualTo(1);
		verify(issueRepository).resolveOpenByDedupeKey(eq("BANK_MISMATCH:PENDING_DEPOSIT:" + settledDep),
				eq(BankReconciliationService.AUTO_RESOLVED_BY), anyString(), eq(NOW));
		verify(issueRepository).resolveOpenByDedupeKey(eq("BANK_MISMATCH:PENDING_WITHDRAWAL:" + settledWdr),
				eq(BankReconciliationService.AUTO_RESOLVED_BY), anyString(), eq(NOW));
		verify(issueRepository, never()).openIfAbsent(any(), eq("BANK_MISMATCH:PENDING_DEPOSIT:" + timeoutDep),
				anyString(), any());
		verify(issueRepository, never()).openIfAbsent(any(), eq("BANK_MISMATCH:PENDING_WITHDRAWAL:" + timeoutWdr),
				anyString(), any());
		verify(issueRepository).openIfAbsent(eq(ReconciliationIssueType.BANK_MISMATCH),
				eq("BANK_MISMATCH:PENDING_WITHDRAWAL:" + mismatchWdr), anyString(), eq(NOW));
	}

	@Test
	void dailyRunEscalatesOldPendingWithdrawalsAndIsolatesFailedWithdrawalLookbackErrors() {
		UUID youngWdr = UUID.randomUUID();
		UUID oldWdr = UUID.randomUUID();
		UUID failedErrWdr = UUID.randomUUID();
		UUID failedOkWdr = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Instant youngTime = NOW.minusSeconds(600);
		Instant oldTime = NOW.minusSeconds(90_000);

		when(mockBankGateway.getDailyStatement(REPORT_DATE, BankReconciliationService.BANK_ZONE))
				.thenReturn(List.of());
		when(queryRepository.findSettledBankTransactions(any(), any())).thenReturn(List.of());
		when(queryRepository.findStalePendingWithdrawals(NOW.minusSeconds(300)))
				.thenReturn(List.of(
						txRow(youngWdr, walletId, "WITHDRAWAL", 150_000L, "PENDING", null, youngTime),
						txRow(oldWdr, walletId, "WITHDRAWAL", 250_000L, "PENDING", null, oldTime)));
		when(withdrawalCommandHandler.reconcilePendingWithdrawal(youngWdr)).thenReturn(ReconcileOutcome.UNRESOLVED);
		when(withdrawalCommandHandler.reconcilePendingWithdrawal(oldWdr)).thenReturn(ReconcileOutcome.UNRESOLVED);

		Instant expectedLookbackStart = NOW.minus(Duration.ofDays(7));
		when(queryRepository.findFailedWithdrawalsBetween(expectedLookbackStart, NOW))
				.thenReturn(List.of(
						txRow(failedErrWdr, walletId, "WITHDRAWAL", 300_000L, "FAILED", null, oldTime),
						txRow(failedOkWdr, walletId, "WITHDRAWAL", 400_000L, "FAILED", null, oldTime)));
		when(withdrawalCommandHandler.ensureCompensatedIfFailed(failedErrWdr))
				.thenThrow(new IllegalStateException("transient db error"));
		when(withdrawalCommandHandler.ensureCompensatedIfFailed(failedOkWdr)).thenReturn(true);
		when(issueRepository.openIfAbsent(any(), anyString(), anyString(), eq(NOW))).thenReturn(true);

		ReconciliationRunResult result = service.reconcileDate(REPORT_DATE);

		assertThat(result.issuesOpened()).isEqualTo(2);
		verify(issueRepository, never()).openIfAbsent(any(), eq("BANK_MISMATCH:PENDING_WITHDRAWAL:" + youngWdr),
				anyString(), any());
		verify(issueRepository).openIfAbsent(eq(ReconciliationIssueType.BANK_MISMATCH),
				eq("BANK_MISMATCH:PENDING_WITHDRAWAL:" + oldWdr), anyString(), eq(NOW));
		verify(issueRepository).openIfAbsent(eq(ReconciliationIssueType.BANK_MISMATCH),
				eq("BANK_MISMATCH:FAILED_WITHDRAWAL_COMPENSATION:" + failedErrWdr), anyString(), eq(NOW));
		verify(withdrawalCommandHandler).ensureCompensatedIfFailed(failedOkWdr);
	}

	@Test
	void longBankReferenceProducesDedupeKeyWithin128Chars() {
		String longRef = "R".repeat(100);
		Instant txTime = Instant.parse("2026-11-21T04:00:00Z");
		when(mockBankGateway.getDailyStatement(REPORT_DATE, BankReconciliationService.BANK_ZONE))
				.thenReturn(List.of(new BankStatementEntry(longRef, "DEPOSIT", UUID.randomUUID(),
						Money.vnd(100_000), "SUCCESS", txTime)));
		when(queryRepository.findSettledBankTransactions(any(), any())).thenReturn(List.of());
		when(issueRepository.openIfAbsent(any(), anyString(), anyString(), eq(NOW))).thenAnswer(inv -> {
			String key = inv.getArgument(1);
			assertThat(key.length()).isLessThanOrEqualTo(128);
			return true;
		});

		assertThat(service.reconcileDate(REPORT_DATE).issuesOpened()).isEqualTo(1);
	}

	@Test
	void failingPendingDepositOpensIssueAndContinuesRemainingPendingDeposits() {
		UUID lockedDep = UUID.randomUUID();
		UUID okDep = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Instant oldTime = NOW.minusSeconds(600);

		when(mockBankGateway.getDailyStatement(REPORT_DATE, BankReconciliationService.BANK_ZONE))
				.thenReturn(List.of());
		when(queryRepository.findSettledBankTransactions(any(), any())).thenReturn(List.of());
		when(queryRepository.findStalePendingDeposits(NOW.minusSeconds(300)))
				.thenReturn(List.of(
						txRow(lockedDep, walletId, "DEPOSIT", 100_000L, "PENDING", "PEND-LOCKED", oldTime),
						txRow(okDep, walletId, "DEPOSIT", 200_000L, "PENDING", "PEND-NEXT", oldTime)));
		when(mockBankGateway.findTransaction("PEND-LOCKED")).thenReturn(Optional.of(
				new BankTransaction("PEND-LOCKED", "DEPOSIT", lockedDep, Money.vnd(100_000), "SUCCESS", null, oldTime)));
		when(mockBankGateway.findTransaction("PEND-NEXT")).thenReturn(Optional.of(
				new BankTransaction("PEND-NEXT", "DEPOSIT", okDep, Money.vnd(200_000), "SUCCESS", null, oldTime)));
		when(depositCommandHandler.deposit(lockedDep, walletId, Money.vnd(100_000), "PEND-LOCKED"))
				.thenThrow(new IllegalStateException("wallet locked"));
		when(depositCommandHandler.deposit(okDep, walletId, Money.vnd(200_000), "PEND-NEXT"))
				.thenReturn(DepositStatus.CONFIRMED);
		when(issueRepository.openIfAbsent(any(), anyString(), anyString(), eq(NOW))).thenReturn(true);

		ReconciliationRunResult result = service.reconcileDate(REPORT_DATE);

		assertThat(result.issuesOpened()).isEqualTo(1);
		verify(depositCommandHandler).deposit(okDep, walletId, Money.vnd(200_000), "PEND-NEXT");
		verify(issueRepository).openIfAbsent(eq(ReconciliationIssueType.BANK_MISMATCH),
				eq("BANK_MISMATCH:PENDING_DEPOSIT:" + lockedDep), anyString(), eq(NOW));
	}

	@Test
	void stalePendingDepositAlreadyVerifiedSettlesWithoutOpeningIssueEvenWhenBankLookupIsEmpty() {
		UUID verifiedDep = UUID.randomUUID();
		UUID unresolvedDep = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Instant oldTime = NOW.minusSeconds(90_000);

		when(mockBankGateway.getDailyStatement(REPORT_DATE, BankReconciliationService.BANK_ZONE))
				.thenReturn(List.of());
		when(queryRepository.findSettledBankTransactions(any(), any())).thenReturn(List.of());
		when(queryRepository.findStalePendingDeposits(NOW.minusSeconds(300)))
				.thenReturn(List.of(
						txRow(verifiedDep, walletId, "DEPOSIT", 400_000L, "PENDING", "VERIFIED-EMPTY", oldTime),
						txRow(unresolvedDep, walletId, "DEPOSIT", 150_000L, "PENDING", "UNKNOWN-EMPTY", oldTime)));
		when(mockBankGateway.findTransaction("VERIFIED-EMPTY")).thenReturn(Optional.empty());
		when(mockBankGateway.findTransaction("UNKNOWN-EMPTY")).thenReturn(Optional.empty());
		when(depositCommandHandler.settleIfAlreadyVerified(verifiedDep, walletId, Money.vnd(400_000), "VERIFIED-EMPTY"))
				.thenReturn(true);
		when(depositCommandHandler.settleIfAlreadyVerified(unresolvedDep, walletId, Money.vnd(150_000), "UNKNOWN-EMPTY"))
				.thenReturn(false);
		when(issueRepository.openIfAbsent(any(), anyString(), anyString(), eq(NOW))).thenReturn(true);

		ReconciliationRunResult result = service.reconcileDate(REPORT_DATE);

		assertThat(result.issuesOpened()).isEqualTo(1);
		verify(depositCommandHandler).settleIfAlreadyVerified(verifiedDep, walletId, Money.vnd(400_000), "VERIFIED-EMPTY");
		verify(issueRepository).resolveOpenByDedupeKey(eq("BANK_MISMATCH:PENDING_DEPOSIT:" + verifiedDep),
				eq(BankReconciliationService.AUTO_RESOLVED_BY), anyString(), eq(NOW));
		verify(depositCommandHandler).settleIfAlreadyVerified(unresolvedDep, walletId, Money.vnd(150_000), "UNKNOWN-EMPTY");
		verify(depositCommandHandler, never()).deposit(eq(verifiedDep), any(), any(), any());
		verify(issueRepository).openIfAbsent(eq(ReconciliationIssueType.BANK_MISMATCH),
				eq("BANK_MISMATCH:PENDING_DEPOSIT:" + unresolvedDep), anyString(), eq(NOW));
	}

	private static TransactionHistoryRow txRow(UUID txId, UUID walletId, String txType, long amount,
			String status, String bankRef, Instant occurredAt) {
		return new TransactionHistoryRow(1L, txId, walletId, txType, "DEPOSIT".equals(txType) ? "IN" : "OUT",
				amount, "VND", status, null, "BANK_SETTLEMENT", bankRef, null, null, occurredAt, occurredAt);
	}
}
