package com.lakeserl.mini_banking.mockbank;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;

import com.lakeserl.mini_banking.shared.domain.Money;

@Component
public class InMemoryMockBankGateway implements MockBankGateway {

	private final Map<String, VerificationStatus> presetDepositStatuses = new ConcurrentHashMap<>();
	private final Map<UUID, PayoutStatus> presetPayoutStatuses = new ConcurrentHashMap<>();
	private final Map<String, BankTransaction> transactions = new ConcurrentHashMap<>();
	private final List<BankStatementEntry> injectedStatementEntries = new CopyOnWriteArrayList<>();

	public void presetDeposit(String bankReference, VerificationStatus status) {
		presetDepositStatuses.put(bankReference, status);
	}

	public void presetPayout(UUID withdrawalId, PayoutStatus status) {
		presetPayoutStatuses.put(withdrawalId, status);
	}

	/** Test helper to record or update a bank-side payout settlement directly. */
	public void recordPayoutTransaction(UUID withdrawalId, Money amount, PayoutStatus status, Instant recordedAt) {
		Objects.requireNonNull(withdrawalId, "withdrawalId must not be null");
		Objects.requireNonNull(amount, "amount must not be null");
		Objects.requireNonNull(status, "status must not be null");
		Objects.requireNonNull(recordedAt, "recordedAt must not be null");
		String bankReference = payoutBankReference(withdrawalId);
		String reason = switch (status) {
			case SUCCESS -> null;
			case REJECTED -> "Mock bank rejected payout";
			case TIMEOUT -> "Bank payout timeout";
		};
		transactions.put(bankReference, new BankTransaction(bankReference, "PAYOUT", withdrawalId, amount,
				status.name(), reason, recordedAt));
	}

	/** Test helper to simulate extra, duplicate, or mismatched rows in the bank's daily statement. */
	public void addStatementEntry(BankStatementEntry entry) {
		injectedStatementEntries.add(Objects.requireNonNull(entry, "entry must not be null"));
	}

	public void reset() {
		presetDepositStatuses.clear();
		presetPayoutStatuses.clear();
		transactions.clear();
		injectedStatementEntries.clear();
	}

	@Override
	public DepositVerificationResult verifyDeposit(UUID depositId, Money amount, String bankReference) {
		Objects.requireNonNull(bankReference, "bankReference must not be null");
		VerificationStatus status = presetDepositStatuses.getOrDefault(bankReference, VerificationStatus.SUCCESS);
		DepositVerificationResult result = switch (status) {
			case SUCCESS -> DepositVerificationResult.success(bankReference);
			case REJECTED -> DepositVerificationResult.rejected("Mock bank rejected deposit: " + bankReference);
			case TIMEOUT -> DepositVerificationResult.timeout();
		};
		transactions.put(bankReference, new BankTransaction(bankReference, "DEPOSIT", depositId, amount,
				result.status().name(), result.reason(), Instant.now()));
		return result;
	}

	@Override
	public PayoutResult payout(UUID withdrawalId, Money amount, String bankAccount) {
		Objects.requireNonNull(withdrawalId, "withdrawalId must not be null");
		Objects.requireNonNull(amount, "amount must not be null");
		String bankReference = payoutBankReference(withdrawalId);
		// Freeze ONLY terminal payout outcomes (SUCCESS/REJECTED) so TIMEOUT remains retryable.
		BankTransaction tx = transactions.compute(bankReference, (ref, existing) -> {
			if (existing != null && ("SUCCESS".equals(existing.status()) || "REJECTED".equals(existing.status()))) {
				return existing;
			}
			PayoutStatus status = presetPayoutStatuses.getOrDefault(withdrawalId, PayoutStatus.SUCCESS);
			PayoutResult result = switch (status) {
				case SUCCESS -> PayoutResult.success(ref);
				case REJECTED -> PayoutResult.rejected("Mock bank rejected payout to " + maskAccount(bankAccount));
				case TIMEOUT -> PayoutResult.timeout();
			};
			return new BankTransaction(ref, "PAYOUT", withdrawalId, amount,
					result.status().name(), result.reason(), Instant.now());
		});
		return switch (tx.status()) {
			case "SUCCESS" -> PayoutResult.success(tx.bankReference());
			case "REJECTED" -> PayoutResult.rejected(tx.reason());
			case "TIMEOUT" -> PayoutResult.timeout();
			default -> throw new IllegalStateException("Unexpected bank payout status: " + tx.status());
		};
	}

	@Override
	public Optional<BankTransaction> findPayout(UUID withdrawalId) {
		Objects.requireNonNull(withdrawalId, "withdrawalId must not be null");
		return findTransaction(payoutBankReference(withdrawalId));
	}

	private static String payoutBankReference(UUID withdrawalId) {
		return "BANK-PAY-" + withdrawalId;
	}

	private static String maskAccount(String bankAccount) {
		if (bankAccount == null || bankAccount.length() <= 4) {
			return "****";
		}
		return "****" + bankAccount.substring(bankAccount.length() - 4);
	}

	@Override
	public Optional<BankTransaction> findTransaction(String bankReference) {
		Objects.requireNonNull(bankReference, "bankReference must not be null");
		return Optional.ofNullable(transactions.get(bankReference));
	}

	@Override
	public List<BankStatementEntry> getDailyStatement(LocalDate date, ZoneId zone) {
		Objects.requireNonNull(date, "date must not be null");
		Objects.requireNonNull(zone, "zone must not be null");
		List<BankStatementEntry> result = new ArrayList<>();
		for (BankTransaction tx : transactions.values()) {
			if (tx.recordedAt().atZone(zone).toLocalDate().equals(date)) {
				result.add(new BankStatementEntry(tx.bankReference(), tx.type(), tx.transactionId(),
						tx.amount(), tx.status(), tx.recordedAt()));
			}
		}
		for (BankStatementEntry extra : injectedStatementEntries) {
			if (extra.recordedAt().atZone(zone).toLocalDate().equals(date)) {
				result.add(extra);
			}
		}
		return result;
	}
}
