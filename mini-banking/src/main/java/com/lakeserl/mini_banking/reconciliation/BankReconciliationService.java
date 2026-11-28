package com.lakeserl.mini_banking.reconciliation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.lakeserl.mini_banking.deposit.command.DepositCommandHandler;
import com.lakeserl.mini_banking.deposit.domain.DepositStatus;
import com.lakeserl.mini_banking.mockbank.MockBankGateway;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.BankStatementEntry;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.BankTransaction;
import com.lakeserl.mini_banking.query.api.QueryRepository;
import com.lakeserl.mini_banking.query.api.QueryRepository.TransactionHistoryRow;
import com.lakeserl.mini_banking.reconciliation.InternalReconciliationService.ReconciliationRunResult;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.withdrawal.command.WithdrawalCommandHandler;
import com.lakeserl.mini_banking.withdrawal.command.WithdrawalCommandHandler.ReconcileOutcome;

import tools.jackson.databind.json.JsonMapper;

/**
 * Bank reconciliation service:
 * <ul>
 *   <li>Every 5 minutes (configurable): settles stale {@code PENDING} deposits and withdrawals
 *       without escalating transient bank timeouts as alerts.</li>
 *   <li>Daily at 00:15 {@code Asia/Ho_Chi_Minh}: compares bank statements against settled system
 *       transactions, escalates {@code PENDING} items unresolved past the escalation threshold
 *       (default 24h), and ensures {@code FAILED} withdrawals within the lookback window are compensated.</li>
 * </ul>
 */
@Service
public class BankReconciliationService {

	private static final Logger log = LoggerFactory.getLogger(BankReconciliationService.class);
	public static final ZoneId BANK_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
	public static final String AUTO_RESOLVED_BY = "SYSTEM_RECONCILIATION";
	public static final long DEFAULT_PENDING_THRESHOLD_SECONDS = 300L;
	public static final long DEFAULT_PENDING_ESCALATION_THRESHOLD_SECONDS = 86_400L;
	public static final int DEFAULT_FAILED_WITHDRAWAL_LOOKBACK_DAYS = 7;

	private final MockBankGateway mockBankGateway;
	private final QueryRepository queryRepository;
	private final DepositCommandHandler depositCommandHandler;
	private final WithdrawalCommandHandler withdrawalCommandHandler;
	private final ReconciliationIssueRepository issueRepository;
	private final Clock clock;
	private final boolean schedulerEnabled;
	private final long pendingDepositThresholdSeconds;
	private final long pendingEscalationThresholdSeconds;
	private final int failedWithdrawalLookbackDays;
	private final JsonMapper mapper = JsonMapper.builder().build();

	@Autowired
	public BankReconciliationService(
			MockBankGateway mockBankGateway,
			QueryRepository queryRepository,
			DepositCommandHandler depositCommandHandler,
			WithdrawalCommandHandler withdrawalCommandHandler,
			ReconciliationIssueRepository issueRepository,
			Clock clock,
			@Value("${reconciliation.bank.enabled:true}") boolean schedulerEnabled,
			@Value("${reconciliation.bank.pending-deposit-threshold-seconds:"
					+ DEFAULT_PENDING_THRESHOLD_SECONDS + "}") long pendingDepositThresholdSeconds,
			@Value("${reconciliation.bank.pending-escalation-threshold-seconds:"
					+ DEFAULT_PENDING_ESCALATION_THRESHOLD_SECONDS + "}") long pendingEscalationThresholdSeconds,
			@Value("${reconciliation.bank.failed-withdrawal-lookback-days:"
					+ DEFAULT_FAILED_WITHDRAWAL_LOOKBACK_DAYS + "}") int failedWithdrawalLookbackDays) {
		this.mockBankGateway = Objects.requireNonNull(mockBankGateway, "mockBankGateway");
		this.queryRepository = Objects.requireNonNull(queryRepository, "queryRepository");
		this.depositCommandHandler = Objects.requireNonNull(depositCommandHandler, "depositCommandHandler");
		this.withdrawalCommandHandler = Objects.requireNonNull(withdrawalCommandHandler, "withdrawalCommandHandler");
		this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
		this.clock = Objects.requireNonNull(clock, "clock");
		if (pendingDepositThresholdSeconds < 0) {
			throw new IllegalArgumentException(
					"reconciliation.bank.pending-deposit-threshold-seconds must be >= 0, got "
							+ pendingDepositThresholdSeconds);
		}
		if (pendingEscalationThresholdSeconds < 0) {
			throw new IllegalArgumentException(
					"reconciliation.bank.pending-escalation-threshold-seconds must be >= 0, got "
							+ pendingEscalationThresholdSeconds);
		}
		if (failedWithdrawalLookbackDays < 1) {
			throw new IllegalArgumentException(
					"reconciliation.bank.failed-withdrawal-lookback-days must be >= 1, got "
							+ failedWithdrawalLookbackDays);
		}
		this.schedulerEnabled = schedulerEnabled;
		this.pendingDepositThresholdSeconds = pendingDepositThresholdSeconds;
		this.pendingEscalationThresholdSeconds = pendingEscalationThresholdSeconds;
		this.failedWithdrawalLookbackDays = failedWithdrawalLookbackDays;
	}

	@Scheduled(cron = "${reconciliation.bank.cron:0 15 0 * * *}", zone = "Asia/Ho_Chi_Minh")
	void scheduledDailyRun() {
		if (!schedulerEnabled) {
			return;
		}
		try {
			LocalDate yesterday = clock.instant().atZone(BANK_ZONE).toLocalDate().minusDays(1);
			reconcileDate(yesterday);
		} catch (RuntimeException ex) {
			log.error("Scheduled daily bank reconciliation failed", ex);
		}
	}

	@Scheduled(cron = "${reconciliation.bank.pending-cron:0 */5 * * * *}", zone = "Asia/Ho_Chi_Minh")
	void scheduledPendingRun() {
		if (!schedulerEnabled) {
			return;
		}
		try {
			reconcileStalePendingTransactions();
		} catch (RuntimeException ex) {
			log.error("Scheduled pending bank reconciliation failed", ex);
		}
	}

	public ReconciliationRunResult reconcileStalePendingTransactions() {
		Instant checkedAt = clock.instant();
		int opened = 0;
		opened += reconcileStalePendingDeposits(checkedAt, false);
		opened += reconcileStalePendingWithdrawals(checkedAt, false);
		log.info("Pending bank reconciliation completed: issuesOpened={}", opened);
		return new ReconciliationRunResult("BANK_PENDING", opened, checkedAt);
	}

	public ReconciliationRunResult reconcileDate(LocalDate date) {
		Objects.requireNonNull(date, "date");
		Instant checkedAt = clock.instant();
		int opened = 0;

		Instant dayStart = date.atStartOfDay(BANK_ZONE).toInstant();
		Instant dayEnd = date.plusDays(1).atStartOfDay(BANK_ZONE).toInstant();

		List<BankStatementEntry> statement = mockBankGateway.getDailyStatement(date, BANK_ZONE);
		List<TransactionHistoryRow> settledSystem = queryRepository.findSettledBankTransactions(dayStart, dayEnd);

		Map<String, BankStatementEntry> bankByRef = new HashMap<>();
		Set<String> duplicateRefs = new HashSet<>();
		for (BankStatementEntry entry : statement) {
			if (!"SUCCESS".equals(entry.status())) {
				continue;
			}
			if (bankByRef.containsKey(entry.bankReference())) {
				if (duplicateRefs.add(entry.bankReference())) {
					if (openIssue("BANK_MISMATCH:DUPLICATE:" + date + ":" + entry.bankReference(),
							Map.of("kind", "DUPLICATE_IN_BANK", "date", date.toString(),
									"bankReference", entry.bankReference()),
							checkedAt)) {
						opened++;
					}
				}
			} else {
				bankByRef.put(entry.bankReference(), entry);
			}
		}

		Map<String, TransactionHistoryRow> systemByRef = new HashMap<>();
		for (TransactionHistoryRow row : settledSystem) {
			if (row.bankReference() != null) {
				systemByRef.put(row.bankReference(), row);
			}
		}

		for (Map.Entry<String, BankStatementEntry> e : bankByRef.entrySet()) {
			String ref = e.getKey();
			BankStatementEntry bank = e.getValue();
			TransactionHistoryRow sys = systemByRef.get(ref);
			if (sys == null) {
				if (openIssue("BANK_MISMATCH:EXTRA_IN_BANK:" + date + ":" + ref,
						Map.of("kind", "EXTRA_IN_BANK", "date", date.toString(), "bankReference", ref,
								"bankAmount", bank.amount().amount(), "bankType", bank.type()),
						checkedAt)) {
					opened++;
				}
				continue;
			}
			String expectedBankType = "DEPOSIT".equals(sys.txType()) ? "DEPOSIT" : "PAYOUT";
			if (sys.amount() != bank.amount().amount() || !expectedBankType.equals(bank.type())) {
				if (openIssue("BANK_MISMATCH:AMOUNT_MISMATCH:" + date + ":" + ref,
						Map.of("kind", "AMOUNT_MISMATCH", "date", date.toString(), "bankReference", ref,
								"systemAmount", sys.amount(), "bankAmount", bank.amount().amount(),
								"systemType", sys.txType(), "bankType", bank.type()),
						checkedAt)) {
					opened++;
				}
			}
		}

		for (Map.Entry<String, TransactionHistoryRow> e : systemByRef.entrySet()) {
			String ref = e.getKey();
			if (!bankByRef.containsKey(ref)) {
				TransactionHistoryRow sys = e.getValue();
				if (openIssue("BANK_MISMATCH:MISSING_IN_BANK:" + date + ":" + ref,
						Map.of("kind", "MISSING_IN_BANK", "date", date.toString(), "bankReference", ref,
								"transactionId", sys.transactionId().toString(), "systemAmount", sys.amount()),
						checkedAt)) {
					opened++;
				}
			}
		}

		opened += reconcileStalePendingDeposits(checkedAt, true);
		opened += reconcileStalePendingWithdrawals(checkedAt, true);
		opened += reconcileUncompensatedFailedWithdrawals(dayStart, checkedAt);

		log.info("Bank reconciliation completed: date={}, issuesOpened={}", date, opened);
		return new ReconciliationRunResult("BANK", opened, checkedAt);
	}

	private int reconcileStalePendingDeposits(Instant checkedAt, boolean escalateUnresolved) {
		int opened = 0;
		Instant settleCutoff = checkedAt.minusSeconds(pendingDepositThresholdSeconds);
		Instant escalationCutoff = checkedAt.minusSeconds(pendingEscalationThresholdSeconds);
		for (TransactionHistoryRow pending : queryRepository.findStalePendingDeposits(settleCutoff)) {
			String dedupeKey = boundDedupeKey("BANK_MISMATCH:PENDING_DEPOSIT:" + pending.transactionId());
			String ref = pending.bankReference();
			try {
				Money money = new Money(pending.amount(), Currency.valueOf(pending.currency()));
				Optional<BankTransaction> bankTx = (ref == null || ref.isBlank())
						? Optional.empty()
						: mockBankGateway.findTransaction(ref);
				if (bankTx.isPresent()
						&& ("SUCCESS".equals(bankTx.get().status()) || "REJECTED".equals(bankTx.get().status()))) {
					try {
						DepositStatus settledStatus = depositCommandHandler.deposit(pending.transactionId(),
								pending.walletId(), money, ref);
						if (settledStatus != DepositStatus.PENDING) {
							issueRepository.resolveOpenByDedupeKey(dedupeKey, AUTO_RESOLVED_BY,
									"Auto-resolved: pending deposit settled (" + settledStatus + ")", checkedAt);
						}
					} catch (RuntimeException ex) {
						log.warn("Failed to settle stale pending deposit {}; opening reconciliation issue",
								pending.transactionId(), ex);
						if (openIssue(dedupeKey,
								Map.of("kind", "PENDING_DEPOSIT_SETTLE_FAILED",
										"depositId", pending.transactionId().toString(),
										"walletId", pending.walletId().toString(),
										"bankReference", ref != null ? ref : "",
										"bankStatus", bankTx.get().status(),
										"error", ex.getClass().getSimpleName()),
								checkedAt)) {
							opened++;
						}
					}
				} else {
					boolean settled = false;
					if (ref != null && !ref.isBlank()) {
						settled = depositCommandHandler.settleIfAlreadyVerified(
								pending.transactionId(), pending.walletId(), money, ref);
					}
					if (settled) {
						issueRepository.resolveOpenByDedupeKey(dedupeKey, AUTO_RESOLVED_BY,
								"Auto-resolved: already-verified pending deposit settled", checkedAt);
					} else if (escalateUnresolved && !pending.occurredAt().isAfter(escalationCutoff)) {
						if (openIssue(dedupeKey,
								Map.of("kind", "PENDING_DEPOSIT_UNRESOLVED",
										"depositId", pending.transactionId().toString(),
										"walletId", pending.walletId().toString(),
										"bankReference", ref != null ? ref : "",
										"bankStatus", bankTx.map(BankTransaction::status).orElse("MISSING")),
								checkedAt)) {
							opened++;
						}
					}
				}
			} catch (RuntimeException ex) {
				log.warn("Failed to reconcile stale pending deposit {}; opening reconciliation issue",
						pending.transactionId(), ex);
				if (openIssue(dedupeKey,
						Map.of("kind", "PENDING_DEPOSIT_SETTLE_FAILED",
								"depositId", pending.transactionId().toString(),
								"walletId", pending.walletId().toString(),
								"bankReference", ref != null ? ref : "",
								"error", ex.getClass().getSimpleName()),
						checkedAt)) {
					opened++;
				}
			}
		}
		return opened;
	}

	private int reconcileStalePendingWithdrawals(Instant checkedAt, boolean escalateUnresolved) {
		int opened = 0;
		Instant settleCutoff = checkedAt.minusSeconds(pendingDepositThresholdSeconds);
		Instant escalationCutoff = checkedAt.minusSeconds(pendingEscalationThresholdSeconds);
		for (TransactionHistoryRow pending : queryRepository.findStalePendingWithdrawals(settleCutoff)) {
			String dedupeKey = boundDedupeKey("BANK_MISMATCH:PENDING_WITHDRAWAL:" + pending.transactionId());
			try {
				ReconcileOutcome outcome = withdrawalCommandHandler.reconcilePendingWithdrawal(pending.transactionId());
				switch (outcome) {
					case SETTLED -> issueRepository.resolveOpenByDedupeKey(dedupeKey, AUTO_RESOLVED_BY,
							"Auto-resolved: pending withdrawal settled", checkedAt);
					case MISMATCH -> {
						Optional<BankTransaction> bankTx = safeFindPayout(pending.transactionId());
						if (openIssue(dedupeKey,
								Map.of("kind", "PENDING_WITHDRAWAL_MISMATCH",
										"withdrawalId", pending.transactionId().toString(),
										"walletId", pending.walletId().toString(),
										"amount", pending.amount(),
										"bankStatus", bankTx.map(BankTransaction::status).orElse("MISSING"),
										"bankAmount", bankTx.map(t -> t.amount() != null ? t.amount().amount() : -1L)
												.orElse(-1L),
										"bankReference", bankTx.map(t -> t.bankReference() != null ? t.bankReference() : "")
												.orElse("")),
								checkedAt)) {
							opened++;
						}
					}
					case UNRESOLVED -> {
						if (escalateUnresolved && !pending.occurredAt().isAfter(escalationCutoff)) {
							Optional<BankTransaction> bankTx = safeFindPayout(pending.transactionId());
							if (openIssue(dedupeKey,
									Map.of("kind", "PENDING_WITHDRAWAL_UNRESOLVED",
											"withdrawalId", pending.transactionId().toString(),
											"walletId", pending.walletId().toString(),
											"amount", pending.amount(),
											"bankStatus", bankTx.map(BankTransaction::status).orElse("MISSING")),
									checkedAt)) {
								opened++;
							}
						}
					}
				}
			} catch (RuntimeException ex) {
				log.warn("Failed to reconcile stale pending withdrawal {}; opening reconciliation issue",
						pending.transactionId(), ex);
				if (openIssue(dedupeKey,
						Map.of("kind", "PENDING_WITHDRAWAL_SETTLE_FAILED",
								"withdrawalId", pending.transactionId().toString(),
								"walletId", pending.walletId().toString(),
								"amount", pending.amount(),
								"error", ex.getClass().getSimpleName()),
						checkedAt)) {
					opened++;
				}
			}
		}
		return opened;
	}

	private int reconcileUncompensatedFailedWithdrawals(Instant dayStart, Instant checkedAt) {
		int opened = 0;
		Instant lookbackStart = checkedAt.minus(Duration.ofDays(failedWithdrawalLookbackDays));
		Instant dayLookback = dayStart.minus(Duration.ofDays(1));
		Instant from = dayLookback.isBefore(lookbackStart) ? dayLookback : lookbackStart;
		for (TransactionHistoryRow failed : queryRepository.findFailedWithdrawalsBetween(from, checkedAt)) {
			String dedupeKey = boundDedupeKey("BANK_MISMATCH:FAILED_WITHDRAWAL_COMPENSATION:" + failed.transactionId());
			try {
				withdrawalCommandHandler.ensureCompensatedIfFailed(failed.transactionId());
				issueRepository.resolveOpenByDedupeKey(dedupeKey, AUTO_RESOLVED_BY,
						"Auto-resolved: failed withdrawal compensation verified", checkedAt);
			} catch (RuntimeException ex) {
				log.warn("Failed to verify compensation for failed withdrawal {}; opening reconciliation issue",
						failed.transactionId(), ex);
				if (openIssue(dedupeKey,
						Map.of("kind", "FAILED_WITHDRAWAL_COMPENSATION_ERROR",
								"withdrawalId", failed.transactionId().toString(),
								"walletId", failed.walletId().toString(),
								"amount", failed.amount(),
								"error", ex.getClass().getSimpleName()),
						checkedAt)) {
					opened++;
				}
			}
		}
		return opened;
	}

	private Optional<BankTransaction> safeFindPayout(UUID withdrawalId) {
		try {
			return mockBankGateway.findPayout(withdrawalId);
		} catch (RuntimeException ex) {
			log.warn("Could not read bank payout details for withdrawal {}", withdrawalId, ex);
			return Optional.empty();
		}
	}

	private boolean openIssue(String dedupeKey, Map<String, Object> details, Instant detectedAt) {
		String json = mapper.writeValueAsString(details);
		return issueRepository.openIfAbsent(ReconciliationIssueType.BANK_MISMATCH, boundDedupeKey(dedupeKey), json,
				detectedAt);
	}

	private static String boundDedupeKey(String dedupeKey) {
		if (dedupeKey.length() <= 128) {
			return dedupeKey;
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			String hash = HexFormat.of().formatHex(digest.digest(dedupeKey.getBytes(StandardCharsets.UTF_8)));
			return dedupeKey.substring(0, 63) + ":" + hash;
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
