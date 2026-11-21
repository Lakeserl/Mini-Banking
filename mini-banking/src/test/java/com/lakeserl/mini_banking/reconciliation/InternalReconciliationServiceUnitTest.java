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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lakeserl.mini_banking.ledger.projection.LedgerQueryRepository;
import com.lakeserl.mini_banking.query.api.QueryRepository;
import com.lakeserl.mini_banking.query.api.QueryRepository.WalletBalanceRow;
import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferSagaStateStore;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;

class InternalReconciliationServiceUnitTest {

	private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

	private LedgerQueryRepository ledgerQuery;
	private TransferSagaStateStore sagaStateStore;
	private QueryRepository queryRepository;
	private WalletRepository walletRepository;
	private ReconciliationIssueRepository issueRepository;
	private InternalReconciliationService service;

	@BeforeEach
	void setUp() {
		ledgerQuery = mock(LedgerQueryRepository.class);
		sagaStateStore = mock(TransferSagaStateStore.class);
		queryRepository = mock(QueryRepository.class);
		walletRepository = mock(WalletRepository.class);
		issueRepository = mock(ReconciliationIssueRepository.class);
		Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
		service = new InternalReconciliationService(ledgerQuery, sagaStateStore, queryRepository,
				walletRepository, issueRepository, clock);
	}

	@Test
	void cleanSystemOpensZeroIssuesEvenWhenInFlightSagaMatchesClearingAndLagExists() {
		UUID walletId = UUID.randomUUID();
		UUID ownerId = UUID.randomUUID();
		Wallet replayed = Wallet.open(walletId, ownerId, Currency.VND);
		replayed.credit(UUID.randomUUID(), Money.vnd(500_000), "BANK_SETTLEMENT", AccountType.SETTLEMENT);
		replayed.markCommitted();

		when(ledgerQuery.totalDebitMinusCredit()).thenReturn(0L);
		when(ledgerQuery.unbalancedTransactions()).thenReturn(List.of());
		// Active DEBITED saga holds 100k in TRANSFER_CLEARING -> exact match, NOT an anomaly
		when(ledgerQuery.accountNetCreditMinusDebit("TRANSFER_CLEARING")).thenReturn(100_000L);
		when(sagaStateStore.sumInFlightClearingAmount()).thenReturn(100_000L);
		// Projection is at version 1 (lagging behind replayed version 2) -> must NOT raise false positive
		when(queryRepository.findAllBalances()).thenReturn(List.of(
				new WalletBalanceRow(walletId, ownerId, "VND", 0L, "ACTIVE", 1L, NOW)));
		when(walletRepository.load(walletId)).thenReturn(Optional.of(replayed));

		InternalReconciliationService.ReconciliationRunResult result = service.run();

		assertThat(result.runType()).isEqualTo("INTERNAL");
		assertThat(result.issuesOpened()).isZero();
		verify(issueRepository, never()).openIfAbsent(any(), anyString(), anyString(), any());
	}

	@Test
	void detectsLedgerInvariantClearingAndBalanceMismatchesWithoutMutatingState() {
		UUID walletId = UUID.randomUUID();
		UUID ownerId = UUID.randomUUID();
		UUID badTxId = UUID.randomUUID();
		Wallet replayed = Wallet.open(walletId, ownerId, Currency.VND);
		replayed.credit(UUID.randomUUID(), Money.vnd(500_000), "BANK_SETTLEMENT", AccountType.SETTLEMENT);
		replayed.markCommitted();

		when(ledgerQuery.totalDebitMinusCredit()).thenReturn(10_000L);
		when(ledgerQuery.unbalancedTransactions()).thenReturn(List.of(badTxId));
		when(ledgerQuery.accountNetCreditMinusDebit("TRANSFER_CLEARING")).thenReturn(50_000L);
		when(sagaStateStore.sumInFlightClearingAmount()).thenReturn(0L);
		// Same version (2 == 2) but projected balance differs from replayed balance
		when(queryRepository.findAllBalances()).thenReturn(List.of(
				new WalletBalanceRow(walletId, ownerId, "VND", 400_000L, "ACTIVE", 2L, NOW)));
		when(walletRepository.load(walletId)).thenReturn(Optional.of(replayed));
		when(issueRepository.openIfAbsent(any(), anyString(), anyString(), eq(NOW))).thenReturn(true);

		InternalReconciliationService.ReconciliationRunResult result = service.run();

		assertThat(result.issuesOpened()).isEqualTo(4);
		verify(issueRepository).openIfAbsent(eq(ReconciliationIssueType.INVARIANT_VIOLATION),
				eq("INVARIANT_VIOLATION:GLOBAL"), anyString(), eq(NOW));
		verify(issueRepository).openIfAbsent(eq(ReconciliationIssueType.INVARIANT_VIOLATION),
				eq("INVARIANT_VIOLATION:TX:" + badTxId), anyString(), eq(NOW));
		verify(issueRepository).openIfAbsent(eq(ReconciliationIssueType.CLEARING_NON_ZERO),
				eq("CLEARING_NON_ZERO:GLOBAL"), anyString(), eq(NOW));
		verify(issueRepository).openIfAbsent(eq(ReconciliationIssueType.BALANCE_MISMATCH),
				eq("BALANCE_MISMATCH:" + walletId), anyString(), eq(NOW));
		verify(walletRepository, never()).save(any());
	}
}
