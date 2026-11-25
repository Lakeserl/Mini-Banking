package com.lakeserl.mini_banking.deposit.infrastructure;

import java.util.Objects;
import java.util.UUID;

import org.postgresql.util.PSQLException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.lakeserl.mini_banking.deposit.domain.exceptions.DuplicateBankReferenceException;
import com.lakeserl.mini_banking.shared.domain.Money;

/**
 * Immutable claim store enforcing global case-insensitive uniqueness on deposit {@code bank_reference}
 * within the same database transaction as {@code DepositRequested}.
 */
@Repository
public class DepositBankReferenceStore {

	public static final String UQ_BANK_REFERENCE_UPPER = "uq_deposit_bank_reference_upper";

	private static final String TRY_CLAIM_SQL = """
			INSERT INTO deposit_bank_references (deposit_id, bank_reference, wallet_id, amount, currency, created_at)
			VALUES (?, ?, ?, ?, ?, now())
			ON CONFLICT (deposit_id) DO NOTHING
			""";

	private final JdbcTemplate jdbc;

	public DepositBankReferenceStore(JdbcTemplate jdbc) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public boolean tryClaim(UUID depositId, String bankReference, UUID walletId, Money amount) {
		Objects.requireNonNull(depositId, "depositId must not be null");
		Objects.requireNonNull(bankReference, "bankReference must not be null");
		Objects.requireNonNull(walletId, "walletId must not be null");
		Objects.requireNonNull(amount, "amount must not be null");
		try {
			int inserted = jdbc.update(TRY_CLAIM_SQL,
					depositId, bankReference, walletId, amount.amount(), amount.currency().name());
			return inserted == 1;
		} catch (DuplicateKeyException e) {
			throw handleDuplicateKeyException(e, bankReference);
		}
	}

	private static RuntimeException handleDuplicateKeyException(DuplicateKeyException e, String bankReference) {
		Throwable cause = e.getCause();
		while (cause != null) {
			if (cause instanceof PSQLException psqlEx) {
				String constraint = psqlEx.getServerErrorMessage() != null
						? psqlEx.getServerErrorMessage().getConstraint()
						: null;
				if (UQ_BANK_REFERENCE_UPPER.equals(constraint)) {
					return new DuplicateBankReferenceException(bankReference);
				}
				return e;
			}
			cause = cause.getCause();
		}
		return e;
	}
}
