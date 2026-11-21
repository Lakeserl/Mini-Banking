package com.lakeserl.mini_banking.mockbank;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.Money;

public interface MockBankGateway {

	enum VerificationStatus {
		SUCCESS,
		REJECTED,
		TIMEOUT
	}

	enum PayoutStatus {
		SUCCESS,
		REJECTED,
		TIMEOUT
	}

	record DepositVerificationResult(VerificationStatus status, String bankReference, String reason) {
		public static DepositVerificationResult success(String bankReference) {
			return new DepositVerificationResult(VerificationStatus.SUCCESS, bankReference, null);
		}

		public static DepositVerificationResult rejected(String reason) {
			return new DepositVerificationResult(VerificationStatus.REJECTED, null, reason);
		}

		public static DepositVerificationResult timeout() {
			return new DepositVerificationResult(VerificationStatus.TIMEOUT, null, "Bank timeout");
		}
	}

	record PayoutResult(PayoutStatus status, String bankReference, String reason) {
		public static PayoutResult success(String bankReference) {
			return new PayoutResult(PayoutStatus.SUCCESS, bankReference, null);
		}

		public static PayoutResult rejected(String reason) {
			return new PayoutResult(PayoutStatus.REJECTED, null, reason);
		}

		public static PayoutResult timeout() {
			return new PayoutResult(PayoutStatus.TIMEOUT, null, "Bank payout timeout");
		}
	}

	record BankTransaction(String bankReference, String type, UUID transactionId, Money amount, String status,
			String reason, Instant recordedAt) {
	}

	record BankStatementEntry(String bankReference, String type, UUID transactionId, Money amount, String status,
			Instant recordedAt) {
	}

	DepositVerificationResult verifyDeposit(UUID depositId, Money amount, String bankReference);

	PayoutResult payout(UUID withdrawalId, Money amount, String bankAccount);

	Optional<BankTransaction> findTransaction(String bankReference);

	List<BankStatementEntry> getDailyStatement(LocalDate date, ZoneId zone);
}
