package com.lakeserl.mini_banking.query.projection;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.lakeserl.mini_banking.deposit.domain.events.DepositBankVerified;
import com.lakeserl.mini_banking.deposit.domain.events.DepositConfirmed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositFailed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositRequested;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.transfer.domain.TransferType;
import com.lakeserl.mini_banking.transfer.domain.events.TransferCompensated;
import com.lakeserl.mini_banking.transfer.domain.events.TransferCompensationStarted;
import com.lakeserl.mini_banking.transfer.domain.events.TransferCompleted;
import com.lakeserl.mini_banking.transfer.domain.events.TransferCreditCompleted;
import com.lakeserl.mini_banking.transfer.domain.events.TransferDebitCompleted;
import com.lakeserl.mini_banking.transfer.domain.events.TransferFailed;
import com.lakeserl.mini_banking.transfer.domain.events.TransferInitiated;
import com.lakeserl.mini_banking.withdrawal.domain.events.WithdrawalConfirmed;
import com.lakeserl.mini_banking.withdrawal.domain.events.WithdrawalFailed;
import com.lakeserl.mini_banking.withdrawal.domain.events.WithdrawalRequested;

/**
 * Idempotent read-side projector maintaining detailed per-wallet {@code transaction_history} rows
 * and {@code daily_reports} (bucketed in {@code Asia/Ho_Chi_Minh}) from deposit, withdrawal,
 * and transfer domain events.
 */
@Service
public class TransactionHistoryProjector {

	public static final String DEFAULT_CONSUMER_GROUP = "tx-history-projector";
	public static final int MAX_CONSUMER_GROUP_LENGTH = 50;
	public static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

	record TxMeta(String txType, long amount, String currency) {
	}

	private static final String INSERT_PROCESSED_SQL = """
			INSERT INTO processed_events (consumer_group, event_id, processed_at)
			VALUES (?, ?, now())
			ON CONFLICT (consumer_group, event_id) DO NOTHING
			""";

	private static final String INSERT_TX_SQL = """
			INSERT INTO transaction_history
				(transaction_id, wallet_id, tx_type, direction, amount, currency, status,
				 counterpart_wallet_id, counterpart_label, bank_reference, original_transfer_id,
				 occurred_at, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (transaction_id, wallet_id) DO NOTHING
			""";

	private static final String UPDATE_STATUS_SQL = """
			UPDATE transaction_history
			SET status = ?,
			    bank_reference = COALESCE(?, bank_reference),
			    failure_reason = COALESCE(?, failure_reason),
			    updated_at = ?
			WHERE transaction_id = ?
			""";

	private static final String SELECT_TX_META_SQL = """
			SELECT tx_type, amount, currency
			FROM transaction_history
			WHERE transaction_id = ?
			LIMIT 1
			""";

	private static final String UPSERT_DAILY_REPORT_SQL = """
			INSERT INTO daily_reports
				(report_date, currency, total_deposit, total_withdrawal, total_transfer, total_refund, tx_count, updated_at)
			VALUES (?, ?, ?, ?, ?, ?, 1, ?)
			ON CONFLICT (report_date, currency) DO UPDATE SET
				total_deposit = daily_reports.total_deposit + EXCLUDED.total_deposit,
				total_withdrawal = daily_reports.total_withdrawal + EXCLUDED.total_withdrawal,
				total_transfer = daily_reports.total_transfer + EXCLUDED.total_transfer,
				total_refund = daily_reports.total_refund + EXCLUDED.total_refund,
				tx_count = daily_reports.tx_count + 1,
				updated_at = EXCLUDED.updated_at
			""";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final String consumerGroup;

	@Autowired
	public TransactionHistoryProjector(JdbcTemplate jdbc, TransactionTemplate tx,
			@Value("${query.consumer.tx-history-group-id:" + DEFAULT_CONSUMER_GROUP + "}") String consumerGroup) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
		this.tx = Objects.requireNonNull(tx, "tx");
		Objects.requireNonNull(consumerGroup, "consumerGroup");
		if (consumerGroup.isBlank() || consumerGroup.length() > MAX_CONSUMER_GROUP_LENGTH) {
			throw new IllegalArgumentException(
					"consumerGroup must be 1.." + MAX_CONSUMER_GROUP_LENGTH + " non-blank characters");
		}
		this.consumerGroup = consumerGroup;
	}

	public TransactionHistoryProjector(JdbcTemplate jdbc, TransactionTemplate tx) {
		this(jdbc, tx, DEFAULT_CONSUMER_GROUP);
	}

	public String consumerGroup() {
		return consumerGroup;
	}

	public boolean project(UUID eventId, DomainEvent event, Instant occurredAt) {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(event, "event");
		Objects.requireNonNull(occurredAt, "occurredAt");
		validateEvent(event);

		Boolean applied = tx.execute(status -> {
			if (jdbc.update(INSERT_PROCESSED_SQL, consumerGroup, eventId) == 0) {
				return false;
			}
			OffsetDateTime ts = OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC);
			switch (event) {
				case DepositRequested e -> jdbc.update(INSERT_TX_SQL,
						e.depositId(), e.walletId(), "DEPOSIT", "IN",
						e.amount().amount(), e.amount().currency().name(), "PENDING",
						null, "BANK_SETTLEMENT", e.bankReference(), null, ts, ts);
				case DepositBankVerified ignored -> {
					// Pre-settlement durability marker; status stays PENDING until DepositConfirmed.
				}
				case DepositConfirmed e -> {
					updateStatus(e.depositId(), "CONFIRMED", null, null, ts);
					incrementDailyReport(e.depositId(), occurredAt, ts);
				}
				case DepositFailed e -> updateStatus(e.depositId(), "FAILED", null, e.reason(), ts);

				case WithdrawalRequested e -> jdbc.update(INSERT_TX_SQL,
						e.withdrawalId(), e.walletId(), "WITHDRAWAL", "OUT",
						e.amount().amount(), e.amount().currency().name(), "PENDING",
						null, maskAccount(e.bankAccount()), null, null, ts, ts);
				case WithdrawalConfirmed e -> {
					updateStatus(e.withdrawalId(), "CONFIRMED", e.bankReference(), null, ts);
					incrementDailyReport(e.withdrawalId(), occurredAt, ts);
				}
				case WithdrawalFailed e -> updateStatus(e.withdrawalId(), "FAILED", null, e.reason(), ts);

				case TransferInitiated e -> {
					String txType = e.type() == TransferType.REFUND ? "REFUND" : "TRANSFER";
					jdbc.update(INSERT_TX_SQL,
							e.transferId(), e.sourceWalletId(), txType, "OUT",
							e.amount().amount(), e.amount().currency().name(), "INITIATED",
							e.destinationWalletId(), null, null, e.originalTransferId(), ts, ts);
					jdbc.update(INSERT_TX_SQL,
							e.transferId(), e.destinationWalletId(), txType, "IN",
							e.amount().amount(), e.amount().currency().name(), "INITIATED",
							e.sourceWalletId(), null, null, e.originalTransferId(), ts, ts);
				}
				case TransferDebitCompleted e -> updateStatus(e.transferId(), "DEBITED", null, null, ts);
				case TransferCreditCompleted ignored -> {
					// Final transition is recorded on TransferCompleted.
				}
				case TransferCompleted e -> {
					updateStatus(e.transferId(), "COMPLETED", null, null, ts);
					incrementDailyReport(e.transferId(), occurredAt, ts);
				}
				case TransferFailed e -> updateStatus(e.transferId(), "FAILED", null, e.reason(), ts);
				case TransferCompensationStarted e -> updateStatus(e.transferId(), "REVERSING", null, e.reason(), ts);
				case TransferCompensated e -> updateStatus(e.transferId(), "REVERSED", null, null, ts);

				default -> throw new IllegalArgumentException(
						"Unsupported transaction history event: " + event.getClass().getName());
			}
			return true;
		});
		return Boolean.TRUE.equals(applied);
	}

	private void updateStatus(UUID transactionId, String status, String bankReference, String failureReason,
			OffsetDateTime updatedAt) {
		int updated = jdbc.update(UPDATE_STATUS_SQL, status, bankReference, failureReason, updatedAt, transactionId);
		if (updated == 0) {
			throw new IllegalStateException("Transaction history row missing for status update: " + transactionId);
		}
	}

	private void incrementDailyReport(UUID transactionId, Instant occurredAt, OffsetDateTime updatedAt) {
		List<TxMeta> rows = jdbc.query(SELECT_TX_META_SQL,
				(rs, rowNum) -> new TxMeta(rs.getString("tx_type"), rs.getLong("amount"), rs.getString("currency")),
				transactionId);
		if (rows.isEmpty()) {
			return;
		}
		TxMeta meta = rows.getFirst();
		LocalDate reportDate = occurredAt.atZone(REPORT_ZONE).toLocalDate();
		long deposit = "DEPOSIT".equals(meta.txType()) ? meta.amount() : 0L;
		long withdrawal = "WITHDRAWAL".equals(meta.txType()) ? meta.amount() : 0L;
		long transfer = "TRANSFER".equals(meta.txType()) ? meta.amount() : 0L;
		long refund = "REFUND".equals(meta.txType()) ? meta.amount() : 0L;
		jdbc.update(UPSERT_DAILY_REPORT_SQL, Date.valueOf(reportDate), meta.currency(),
				deposit, withdrawal, transfer, refund, updatedAt);
	}

	static String maskAccount(String bankAccount) {
		if (bankAccount == null || bankAccount.length() <= 4) {
			return "****";
		}
		return "****" + bankAccount.substring(bankAccount.length() - 4);
	}

	private static void validateEvent(DomainEvent event) {
		switch (event) {
			case DepositRequested e -> validateAmount(e.depositId(), e.walletId(), e.amount());
			case DepositBankVerified e -> Objects.requireNonNull(e.depositId(), "depositId");
			case DepositConfirmed e -> Objects.requireNonNull(e.depositId(), "depositId");
			case DepositFailed e -> Objects.requireNonNull(e.depositId(), "depositId");
			case WithdrawalRequested e -> validateAmount(e.withdrawalId(), e.walletId(), e.amount());
			case WithdrawalConfirmed e -> Objects.requireNonNull(e.withdrawalId(), "withdrawalId");
			case WithdrawalFailed e -> Objects.requireNonNull(e.withdrawalId(), "withdrawalId");
			case TransferInitiated e -> {
				if (e.transferId() == null || e.sourceWalletId() == null || e.destinationWalletId() == null
						|| e.type() == null || e.amount() == null || !e.amount().isPositive()
						|| e.amount().currency() != Currency.VND) {
					throw new IllegalArgumentException("Invalid TransferInitiated event");
				}
			}
			case TransferDebitCompleted e -> Objects.requireNonNull(e.transferId(), "transferId");
			case TransferCreditCompleted e -> Objects.requireNonNull(e.transferId(), "transferId");
			case TransferCompleted e -> Objects.requireNonNull(e.transferId(), "transferId");
			case TransferFailed e -> Objects.requireNonNull(e.transferId(), "transferId");
			case TransferCompensationStarted e -> Objects.requireNonNull(e.transferId(), "transferId");
			case TransferCompensated e -> Objects.requireNonNull(e.transferId(), "transferId");
			default -> throw new IllegalArgumentException(
					"Unsupported transaction history event: " + event.getClass().getName());
		}
	}

	private static void validateAmount(UUID txId, UUID walletId, Money amount) {
		if (txId == null || walletId == null || amount == null || !amount.isPositive()
				|| amount.currency() != Currency.VND) {
			throw new IllegalArgumentException("Invalid transaction amount or identity");
		}
	}
}
