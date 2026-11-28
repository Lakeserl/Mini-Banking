package com.lakeserl.mini_banking.mockbank;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.Money;

/**
 * Bank gateway abstraction for external deposit verification and cash-out payouts.
 *
 * <p>Production adapters <b>MUST</b> enforce idempotency keyed by {@code withdrawalId} on
 * {@link #payout(UUID, Money, String)} (returning the settled terminal outcome on retries and never
 * executing a second cash payout for the same {@code withdrawalId}) and configure bounded HTTP
 * connect/read timeouts ({@code <= 5s}) so worker threads and per-withdrawal locks are not held
 * indefinitely.
 */
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

	/**
	 * Initiates or replays a payout keyed by {@code withdrawalId}. Production adapters MUST enforce
	 * idempotency by {@code withdrawalId} and bound network timeouts to {@code <= 5s}.
	 */
	PayoutResult payout(UUID withdrawalId, Money amount, String bankAccount);

	default Optional<BankTransaction> findPayout(UUID withdrawalId) {
		return Optional.empty();
	}

	Optional<BankTransaction> findTransaction(String bankReference);

	List<BankStatementEntry> getDailyStatement(LocalDate date, ZoneId zone);
}
