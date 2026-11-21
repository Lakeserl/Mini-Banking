package com.lakeserl.mini_banking.query.api;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.lakeserl.mini_banking.query.api.CursorCodec.Cursor;

/**
 * Read-side repository reading exclusively from query projection tables ({@code wallet_balances},
 * {@code transaction_history}, {@code daily_reports}).
 */
@Repository
public class QueryRepository {

	public record WalletBalanceRow(UUID walletId, UUID ownerId, String currency, long balance, String status,
			long version, Instant updatedAt) {
	}

	public record TransactionHistoryRow(long id, UUID transactionId, UUID walletId, String txType, String direction,
			long amount, String currency, String status, UUID counterpartWalletId, String counterpartLabel,
			String bankReference, UUID originalTransferId, String failureReason, Instant occurredAt,
			Instant updatedAt) {
	}

	public record DailyReportRow(LocalDate date, String currency, long totalDeposit, long totalWithdrawal,
			long totalTransfer, long totalRefund, long transactionCount) {
	}

	private static final String SELECT_BALANCE_SQL = """
			SELECT wallet_id, owner_id, currency, balance, status, aggregate_version, updated_at
			FROM wallet_balances
			WHERE wallet_id = ?
			""";

	private static final String SELECT_ALL_BALANCES_SQL = """
			SELECT wallet_id, owner_id, currency, balance, status, aggregate_version, updated_at
			FROM wallet_balances
			ORDER BY wallet_id ASC
			""";

	private static final String SELECT_TX_FIRST_PAGE_SQL = """
			SELECT id, transaction_id, wallet_id, tx_type, direction, amount, currency, status,
			       counterpart_wallet_id, counterpart_label, bank_reference, original_transfer_id,
			       failure_reason, occurred_at, updated_at
			FROM transaction_history
			WHERE wallet_id = ?
			ORDER BY occurred_at DESC, id DESC
			LIMIT ?
			""";

	private static final String SELECT_TX_AFTER_CURSOR_SQL = """
			SELECT id, transaction_id, wallet_id, tx_type, direction, amount, currency, status,
			       counterpart_wallet_id, counterpart_label, bank_reference, original_transfer_id,
			       failure_reason, occurred_at, updated_at
			FROM transaction_history
			WHERE wallet_id = ?
			  AND (occurred_at < ? OR (occurred_at = ? AND id < ?))
			ORDER BY occurred_at DESC, id DESC
			LIMIT ?
			""";

	private static final String SELECT_DAILY_REPORTS_SQL = """
			SELECT report_date, currency, total_deposit, total_withdrawal, total_transfer, total_refund, tx_count
			FROM daily_reports
			WHERE report_date BETWEEN ? AND ?
			ORDER BY report_date DESC, currency ASC
			""";

	private static final RowMapper<WalletBalanceRow> BALANCE_MAPPER = (rs, i) -> new WalletBalanceRow(
			rs.getObject("wallet_id", UUID.class),
			rs.getObject("owner_id", UUID.class),
			rs.getString("currency"),
			rs.getLong("balance"),
			rs.getString("status"),
			rs.getLong("aggregate_version"),
			rs.getObject("updated_at", OffsetDateTime.class).toInstant());

	private static final RowMapper<TransactionHistoryRow> TX_MAPPER = (rs, i) -> new TransactionHistoryRow(
			rs.getLong("id"),
			rs.getObject("transaction_id", UUID.class),
			rs.getObject("wallet_id", UUID.class),
			rs.getString("tx_type"),
			rs.getString("direction"),
			rs.getLong("amount"),
			rs.getString("currency"),
			rs.getString("status"),
			rs.getObject("counterpart_wallet_id", UUID.class),
			rs.getString("counterpart_label"),
			rs.getString("bank_reference"),
			rs.getObject("original_transfer_id", UUID.class),
			rs.getString("failure_reason"),
			rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
			rs.getObject("updated_at", OffsetDateTime.class).toInstant());

	private static final RowMapper<DailyReportRow> DAILY_MAPPER = (rs, i) -> new DailyReportRow(
			rs.getDate("report_date").toLocalDate(),
			rs.getString("currency"),
			rs.getLong("total_deposit"),
			rs.getLong("total_withdrawal"),
			rs.getLong("total_transfer"),
			rs.getLong("total_refund"),
			rs.getLong("tx_count"));

	private final JdbcTemplate jdbc;

	public QueryRepository(JdbcTemplate jdbc) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
	}

	public Optional<WalletBalanceRow> findBalance(UUID walletId) {
		Objects.requireNonNull(walletId, "walletId");
		return jdbc.query(SELECT_BALANCE_SQL, BALANCE_MAPPER, walletId).stream().findFirst();
	}

	public List<WalletBalanceRow> findAllBalances() {
		return jdbc.query(SELECT_ALL_BALANCES_SQL, BALANCE_MAPPER);
	}

	public List<TransactionHistoryRow> findTransactions(UUID walletId, Cursor cursor, int fetchSize) {
		Objects.requireNonNull(walletId, "walletId");
		if (fetchSize <= 0) {
			throw new IllegalArgumentException("fetchSize must be positive");
		}
		if (cursor == null) {
			return jdbc.query(SELECT_TX_FIRST_PAGE_SQL, TX_MAPPER, walletId, fetchSize);
		}
		OffsetDateTime cursorTs = OffsetDateTime.ofInstant(cursor.occurredAt(), ZoneOffset.UTC);
		return jdbc.query(SELECT_TX_AFTER_CURSOR_SQL, TX_MAPPER, walletId, cursorTs, cursorTs, cursor.id(), fetchSize);
	}

	public List<DailyReportRow> findDailyReports(LocalDate from, LocalDate to) {
		Objects.requireNonNull(from, "from");
		Objects.requireNonNull(to, "to");
		return jdbc.query(SELECT_DAILY_REPORTS_SQL, DAILY_MAPPER, Date.valueOf(from), Date.valueOf(to));
	}
}
