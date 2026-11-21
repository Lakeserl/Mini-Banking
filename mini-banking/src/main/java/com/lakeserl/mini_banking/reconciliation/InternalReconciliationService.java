package com.lakeserl.mini_banking.reconciliation;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.lakeserl.mini_banking.ledger.projection.LedgerQueryRepository;
import com.lakeserl.mini_banking.query.api.QueryRepository;
import com.lakeserl.mini_banking.query.api.QueryRepository.WalletBalanceRow;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferSagaStateStore;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * Hourly internal reconciliation verifying double-entry ledger invariants, {@code TRANSFER_CLEARING}
 * net balance against in-flight sagas, and {@code wallet_balances} against event-store replay.
 * Never mutates balances or ledger entries.
 */
@Service
public class InternalReconciliationService {

	private static final Logger log = LoggerFactory.getLogger(InternalReconciliationService.class);
	private static final String CLEARING_ACCOUNT = "TRANSFER_CLEARING";

	public record ReconciliationRunResult(String runType, int issuesOpened, Instant checkedAt) {
	}

	private final LedgerQueryRepository ledgerQuery;
	private final TransferSagaStateStore sagaStateStore;
	private final QueryRepository queryRepository;
	private final WalletRepository walletRepository;
	private final ReconciliationIssueRepository issueRepository;
	private final Clock clock;
	private final boolean schedulerEnabled;
	private final JsonMapper mapper = JsonMapper.builder().build();

	@org.springframework.beans.factory.annotation.Autowired
	public InternalReconciliationService(
			LedgerQueryRepository ledgerQuery,
			TransferSagaStateStore sagaStateStore,
			QueryRepository queryRepository,
			WalletRepository walletRepository,
			ReconciliationIssueRepository issueRepository,
			Clock clock,
			@Value("${reconciliation.internal.enabled:true}") boolean schedulerEnabled) {
		this.ledgerQuery = Objects.requireNonNull(ledgerQuery, "ledgerQuery");
		this.sagaStateStore = Objects.requireNonNull(sagaStateStore, "sagaStateStore");
		this.queryRepository = Objects.requireNonNull(queryRepository, "queryRepository");
		this.walletRepository = Objects.requireNonNull(walletRepository, "walletRepository");
		this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.schedulerEnabled = schedulerEnabled;
	}

	public InternalReconciliationService(
			LedgerQueryRepository ledgerQuery,
			TransferSagaStateStore sagaStateStore,
			QueryRepository queryRepository,
			WalletRepository walletRepository,
			ReconciliationIssueRepository issueRepository,
			Clock clock) {
		this(ledgerQuery, sagaStateStore, queryRepository, walletRepository, issueRepository, clock, false);
	}

	@Scheduled(fixedDelayString = "${reconciliation.internal.interval-ms:3600000}")
	void scheduledRun() {
		if (!schedulerEnabled) {
			return;
		}
		try {
			run();
		} catch (RuntimeException ex) {
			log.error("Scheduled internal reconciliation failed", ex);
		}
	}

	public ReconciliationRunResult run() {
		Instant checkedAt = clock.instant();
		int opened = 0;

		long globalDiff = ledgerQuery.totalDebitMinusCredit();
		if (globalDiff != 0L) {
			if (openIssue(ReconciliationIssueType.INVARIANT_VIOLATION, "INVARIANT_VIOLATION:GLOBAL",
					Map.of("debitMinusCredit", globalDiff), checkedAt)) {
				opened++;
			}
		}

		List<UUID> unbalanced = ledgerQuery.unbalancedTransactions();
		for (UUID txId : unbalanced) {
			if (openIssue(ReconciliationIssueType.INVARIANT_VIOLATION, "INVARIANT_VIOLATION:TX:" + txId,
					Map.of("transactionId", txId.toString()), checkedAt)) {
				opened++;
			}
		}

		long clearingBalance = ledgerQuery.accountNetCreditMinusDebit(CLEARING_ACCOUNT);
		long expectedClearing = sagaStateStore.sumInFlightClearingAmount();
		if (clearingBalance != expectedClearing) {
			if (openIssue(ReconciliationIssueType.CLEARING_NON_ZERO, "CLEARING_NON_ZERO:GLOBAL",
					Map.of("clearingBalance", clearingBalance, "expectedInFlightClearing", expectedClearing),
					checkedAt)) {
				opened++;
			}
		}

		for (WalletBalanceRow projected : queryRepository.findAllBalances()) {
			Optional<Wallet> replayedOpt = walletRepository.load(projected.walletId());
			if (replayedOpt.isEmpty()) {
				if (openIssue(ReconciliationIssueType.BALANCE_MISMATCH, "BALANCE_MISMATCH:" + projected.walletId(),
						Map.of("walletId", projected.walletId().toString(), "reason", "Missing in event store"),
						checkedAt)) {
					opened++;
				}
				continue;
			}
			Wallet replayed = replayedOpt.get();
			if (projected.version() == replayed.committedVersion()
					&& projected.balance() != replayed.balance().amount()) {
				if (openIssue(ReconciliationIssueType.BALANCE_MISMATCH, "BALANCE_MISMATCH:" + projected.walletId(),
						Map.of("walletId", projected.walletId().toString(),
								"version", projected.version(),
								"projectedBalance", projected.balance(),
								"replayedBalance", replayed.balance().amount()),
						checkedAt)) {
					opened++;
				}
			}
		}

		log.info("Internal reconciliation completed: issuesOpened={}", opened);
		return new ReconciliationRunResult("INTERNAL", opened, checkedAt);
	}

	private boolean openIssue(ReconciliationIssueType type, String dedupeKey, Map<String, Object> details,
			Instant detectedAt) {
		String json = mapper.writeValueAsString(details);
		return issueRepository.openIfAbsent(type, dedupeKey, json, detectedAt);
	}
}
