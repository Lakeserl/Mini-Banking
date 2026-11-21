package com.lakeserl.mini_banking.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lakeserl.mini_banking.deposit.command.DepositCommandHandler;
import com.lakeserl.mini_banking.mockbank.MockBankGateway;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.BankStatementEntry;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.BankTransaction;
import com.lakeserl.mini_banking.query.api.QueryRepository;
import com.lakeserl.mini_banking.query.api.QueryRepository.TransactionHistoryRow;
import com.lakeserl.mini_banking.reconciliation.InternalReconciliationService.ReconciliationRunResult;
import com.lakeserl.mini_banking.shared.domain.Money;

class BankReconciliationServiceUnitTest {

	private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
	private static final LocalDate REPORT_DATE = LocalDate.of(2026, 11, 21);

	private MockBankGateway mockBankGateway;
	private QueryRepository queryRepository;
	private DepositCommandHandler depositCommandHandler;
	private ReconciliationIssueRepository issueRepository;
	private BankReconciliationService service;

	@BeforeEach
	void setUp() {
		mockBankGateway = mock(MockBankGateway.class);
		queryRepository = mock(QueryRepository.class);
		depositCommandHandler = mock(DepositCommandHandler.class);
		issueRepository = mock(ReconciliationIssueRepository.class);
		service = new BankReconciliationService(mockBankGateway, queryRepository, depositCommandHandler,
				issueRepository, Clock.fixed(NOW, ZoneOffset.UTC), false, 300L);
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
		when(queryRepository.findStalePendingDeposits(any())).thenReturn(List.of());

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
		when(queryRepository.findStalePendingDeposits(any())).thenReturn(List.of());
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
	void stalePendingDepositsAreReverifiedWithBankOrFlaggedWhenStillUnresolved() {
		UUID resolvedDep = UUID.randomUUID();
		UUID unresolvedDep = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Instant oldTime = NOW.minusSeconds(600);

		when(mockBankGateway.getDailyStatement(REPORT_DATE, BankReconciliationService.BANK_ZONE))
				.thenReturn(List.of());
		when(queryRepository.findSettledBankTransactions(any(), any())).thenReturn(List.of());
		when(queryRepository.findStalePendingDeposits(NOW.minusSeconds(300)))
				.thenReturn(List.of(
						txRow(resolvedDep, walletId, "DEPOSIT", 200_000L, "PENDING", "PEND-OK", oldTime),
						txRow(unresolvedDep, walletId, "DEPOSIT", 300_000L, "PENDING", "PEND-STUCK", oldTime)));
		when(mockBankGateway.findTransaction("PEND-OK")).thenReturn(Optional.of(
				new BankTransaction("PEND-OK", "DEPOSIT", resolvedDep, Money.vnd(200_000), "SUCCESS", null, oldTime)));
		when(mockBankGateway.findTransaction("PEND-STUCK")).thenReturn(Optional.of(
				new BankTransaction("PEND-STUCK", "DEPOSIT", unresolvedDep, Money.vnd(300_000), "TIMEOUT", "timeout", oldTime)));
		when(issueRepository.openIfAbsent(any(), anyString(), anyString(), eq(NOW))).thenReturn(true);

		ReconciliationRunResult result = service.reconcileDate(REPORT_DATE);

		assertThat(result.issuesOpened()).isEqualTo(1);
		verify(depositCommandHandler).deposit(resolvedDep, walletId, Money.vnd(200_000), "PEND-OK");
		verify(depositCommandHandler, never()).deposit(eq(unresolvedDep), any(), any(), any());
		verify(issueRepository).openIfAbsent(eq(ReconciliationIssueType.BANK_MISMATCH),
				eq("BANK_MISMATCH:PENDING_DEPOSIT:" + unresolvedDep), anyString(), eq(NOW));
	}

	private static TransactionHistoryRow txRow(UUID txId, UUID walletId, String txType, long amount,
			String status, String bankRef, Instant occurredAt) {
		return new TransactionHistoryRow(1L, txId, walletId, txType, "DEPOSIT".equals(txType) ? "IN" : "OUT",
				amount, "VND", status, null, "BANK_SETTLEMENT", bankRef, null, null, occurredAt, occurredAt);
	}
}
