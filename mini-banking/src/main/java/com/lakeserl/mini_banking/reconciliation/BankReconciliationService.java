package com.lakeserl.mini_banking.reconciliation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.lakeserl.mini_banking.deposit.command.DepositCommandHandler;
import com.lakeserl.mini_banking.mockbank.MockBankGateway;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.BankStatementEntry;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.BankTransaction;
import com.lakeserl.mini_banking.query.api.QueryRepository;
import com.lakeserl.mini_banking.query.api.QueryRepository.TransactionHistoryRow;
import com.lakeserl.mini_banking.reconciliation.InternalReconciliationService.ReconciliationRunResult;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;

import tools.jackson.databind.json.JsonMapper;

/**
 * Daily reconciliation comparing {@link MockBankGateway} statements (in {@code Asia/Ho_Chi_Minh})
 * against settled system deposits and withdrawals, plus re-verifying stale {@code PENDING} deposits.
 */
@Service
public class BankReconciliationService {

	private static final Logger log = LoggerFactory.getLogger(BankReconciliationService.class);
	public static final ZoneId BANK_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

	private final MockBankGateway mockBankGateway;
	private final QueryRepository queryRepository;
	private final DepositCommandHandler depositCommandHandler;
	private final ReconciliationIssueRepository issueRepository;
	private final Clock clock;
	private final boolean schedulerEnabled;
	private final long pendingDepositThresholdSeconds;
	private final JsonMapper mapper = JsonMapper.builder().build();

	@Autowired
	public BankReconciliationService(
			MockBankGateway mockBankGateway,
			QueryRepository queryRepository,
			DepositCommandHandler depositCommandHandler,
			ReconciliationIssueRepository issueRepository,
			Clock clock,
			@Value("${reconciliation.bank.enabled:true}") boolean schedulerEnabled,
			@Value("${reconciliation.bank.pending-deposit-threshold-seconds:300}") long pendingDepositThresholdSeconds) {
		this.mockBankGateway = Objects.requireNonNull(mockBankGateway, "mockBankGateway");
		this.queryRepository = Objects.requireNonNull(queryRepository, "queryRepository");
		this.depositCommandHandler = Objects.requireNonNull(depositCommandHandler, "depositCommandHandler");
		this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.schedulerEnabled = schedulerEnabled;
		this.pendingDepositThresholdSeconds = pendingDepositThresholdSeconds;
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

		opened += reconcileStalePendingDeposits(checkedAt);

		log.info("Bank reconciliation completed: date={}, issuesOpened={}", date, opened);
		return new ReconciliationRunResult("BANK", opened, checkedAt);
	}

	private int reconcileStalePendingDeposits(Instant checkedAt) {
		int opened = 0;
		Instant cutoff = checkedAt.minusSeconds(pendingDepositThresholdSeconds);
		for (TransactionHistoryRow pending : queryRepository.findStalePendingDeposits(cutoff)) {
			String ref = pending.bankReference();
			Money money = new Money(pending.amount(), Currency.valueOf(pending.currency()));
			Optional<BankTransaction> bankTx = (ref == null || ref.isBlank())
					? Optional.empty()
					: mockBankGateway.findTransaction(ref);
			if (bankTx.isPresent() && ("SUCCESS".equals(bankTx.get().status()) || "REJECTED".equals(bankTx.get().status()))) {
				try {
					depositCommandHandler.deposit(pending.transactionId(), pending.walletId(), money, ref);
				} catch (RuntimeException ex) {
					log.warn("Failed to settle stale pending deposit {}; opening reconciliation issue",
							pending.transactionId(), ex);
					if (openIssue("BANK_MISMATCH:PENDING_DEPOSIT:" + pending.transactionId(),
							Map.of("kind", "PENDING_DEPOSIT_SETTLE_FAILED",
									"depositId", pending.transactionId().toString(),
									"walletId", pending.walletId().toString(),
									"bankReference", ref != null ? ref : "",
									"error", ex.getClass().getSimpleName()),
							checkedAt)) {
						opened++;
					}
				}
			} else {
				boolean settled = false;
				if (ref != null && !ref.isBlank()) {
					try {
						settled = depositCommandHandler.settleIfAlreadyVerified(
								pending.transactionId(), pending.walletId(), money, ref);
					} catch (RuntimeException ex) {
						log.warn("Failed to settle already-verified pending deposit {}; opening reconciliation issue",
								pending.transactionId(), ex);
						if (openIssue("BANK_MISMATCH:PENDING_DEPOSIT:" + pending.transactionId(),
								Map.of("kind", "PENDING_DEPOSIT_SETTLE_FAILED",
										"depositId", pending.transactionId().toString(),
										"walletId", pending.walletId().toString(),
										"bankReference", ref,
										"error", ex.getClass().getSimpleName()),
								checkedAt)) {
							opened++;
						}
						continue;
					}
				}
				if (!settled) {
					if (openIssue("BANK_MISMATCH:PENDING_DEPOSIT:" + pending.transactionId(),
							Map.of("kind", "PENDING_DEPOSIT_UNRESOLVED",
									"depositId", pending.transactionId().toString(),
									"walletId", pending.walletId().toString(),
									"bankReference", ref != null ? ref : ""),
							checkedAt)) {
						opened++;
					}
				}
			}
		}
		return opened;
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
