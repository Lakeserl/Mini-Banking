package com.lakeserl.mini_banking.ledger.projection;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-side query repository for double-entry ledger balance and reconciliation checks.
 */
@Repository
public class LedgerQueryRepository {

	private static final String TOTAL_DEBIT_MINUS_CREDIT_SQL = """
			SELECT COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END), 0)
			FROM ledger_entries
			""";

	private static final String ACCOUNT_NET_CREDIT_MINUS_DEBIT_SQL = """
			SELECT COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END), 0)
			FROM ledger_entries
			WHERE account_id = ?
			""";

	private static final String ACCOUNT_NET_DEBIT_MINUS_CREDIT_SQL = """
			SELECT COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END), 0)
			FROM ledger_entries
			WHERE account_id = ?
			""";

	private static final String UNBALANCED_TRANSACTIONS_SQL = """
			SELECT transaction_id
			FROM ledger_entries
			GROUP BY transaction_id
			HAVING SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END) <> 0
			ORDER BY transaction_id
			""";

	private final JdbcTemplate jdbc;

	public LedgerQueryRepository(JdbcTemplate jdbc) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
	}

	public long totalDebitMinusCredit() {
		Long total = jdbc.queryForObject(TOTAL_DEBIT_MINUS_CREDIT_SQL, Long.class);
		return total != null ? total : 0L;
	}

	public long accountNetCreditMinusDebit(String accountId) {
		requireAccountId(accountId);
		Long net = jdbc.queryForObject(ACCOUNT_NET_CREDIT_MINUS_DEBIT_SQL, Long.class, accountId);
		return net != null ? net : 0L;
	}

	public long accountNetDebitMinusCredit(String accountId) {
		requireAccountId(accountId);
		Long net = jdbc.queryForObject(ACCOUNT_NET_DEBIT_MINUS_CREDIT_SQL, Long.class, accountId);
		return net != null ? net : 0L;
	}

	public List<UUID> unbalancedTransactions() {
		return jdbc.query(UNBALANCED_TRANSACTIONS_SQL, (rs, rowNum) -> rs.getObject("transaction_id", UUID.class));
	}

	private static void requireAccountId(String accountId) {
		Objects.requireNonNull(accountId, "accountId");
		if (accountId.isBlank()) {
			throw new IllegalArgumentException("accountId must not be blank");
		}
	}
}
