package com.lakeserl.mini_banking.transfer.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.postgresql.util.PSQLException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.transfer.domain.TransferStatus;
import com.lakeserl.mini_banking.transfer.domain.TransferType;
import com.lakeserl.mini_banking.transfer.domain.exceptions.InvalidTransferStateException;
import com.lakeserl.mini_banking.transfer.domain.exceptions.TransferNotFoundException;
import com.lakeserl.mini_banking.transfer.domain.exceptions.TransferPolicyViolationException;

/**
 * Command-side operational index and policy store backed by {@code transfer_saga_state}.
 * Tracks active transfer sagas, enforces serialized 24h outgoing daily limits for standard transfers,
 * and enforces single active/completed refund uniqueness per original transfer.
 */
@Repository
public class TransferSagaStateStore {

	public static final String PK_TRANSFER_ID = "transfer_saga_state_pkey";
	public static final String UQ_ACTIVE_REFUND = "uq_transfer_active_refund";

	public record StuckSaga(UUID transferId, TransferStatus status, int retryCount) {
	}

	public record TransferSagaSummary(UUID transferId, UUID sourceWalletId, UUID destinationWalletId, long amount,
			Currency currency, TransferType type, UUID originalTransferId, TransferStatus status, int retryCount,
			String failureReason, Instant createdAt, Instant updatedAt) {
	}

	private static final Duration DAILY_WINDOW = Duration.ofHours(24);

	private static final String ADVISORY_LOCK = "SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))";

	private static final String EXISTS_BY_ID = "SELECT EXISTS (SELECT 1 FROM transfer_saga_state WHERE transfer_id = ?)";

	private static final String SUM_OUTGOING_24H = """
			SELECT COALESCE(SUM(amount), 0)
			FROM transfer_saga_state
			WHERE source_wallet_id = ?
			  AND transfer_type = 'STANDARD'
			  AND status IN ('INITIATED', 'DEBITED', 'COMPLETED')
			  AND created_at >= ?
			""";

	private static final String INSERT_INITIATED = """
			INSERT INTO transfer_saga_state (
			    transfer_id, source_wallet_id, dest_wallet_id, amount, currency,
			    transfer_type, original_transfer_id, status, retry_count, failure_reason,
			    created_at, updated_at
			) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, ?, ?)
			ON CONFLICT (transfer_id) DO NOTHING
			""";

	private static final String UPDATE_STATUS = """
			UPDATE transfer_saga_state
			SET status = ?, failure_reason = ?, updated_at = ?
			WHERE transfer_id = ?
			""";

	private static final String FIND_STUCK = """
			SELECT transfer_id, status, retry_count
			FROM transfer_saga_state
			WHERE status IN ('INITIATED', 'DEBITED', 'REVERSING')
			  AND updated_at <= ?
			ORDER BY updated_at ASC LIMIT ?
			""";

	private static final String INCREMENT_RETRY = """
			UPDATE transfer_saga_state
			SET retry_count = retry_count + 1
			WHERE transfer_id = ?
			RETURNING retry_count
			""";

	private static final String FIND_BY_ID = """
			SELECT transfer_id, source_wallet_id, dest_wallet_id, amount, currency,
			       transfer_type, original_transfer_id, status, retry_count, failure_reason,
			       created_at, updated_at
			FROM transfer_saga_state
			WHERE transfer_id = ?
			""";

	private static final RowMapper<StuckSaga> STUCK_SAGA_ROW_MAPPER = (rs, i) -> new StuckSaga(
			rs.getObject("transfer_id", UUID.class),
			TransferStatus.valueOf(rs.getString("status")),
			rs.getInt("retry_count"));

	private static final RowMapper<TransferSagaSummary> SUMMARY_ROW_MAPPER = (rs, i) -> new TransferSagaSummary(
			rs.getObject("transfer_id", UUID.class),
			rs.getObject("source_wallet_id", UUID.class),
			rs.getObject("dest_wallet_id", UUID.class),
			rs.getLong("amount"),
			Currency.valueOf(rs.getString("currency")),
			TransferType.valueOf(rs.getString("transfer_type")),
			rs.getObject("original_transfer_id", UUID.class),
			TransferStatus.valueOf(rs.getString("status")),
			rs.getInt("retry_count"),
			rs.getString("failure_reason"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant(),
			rs.getObject("updated_at", OffsetDateTime.class).toInstant());

	private final JdbcTemplate jdbc;
	private final Clock clock;

	public TransferSagaStateStore(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	/**
	 * Inserts a new saga state row in {@link TransferStatus#INITIATED} within the caller's active transaction.
	 * Standard transfers acquire a transaction-scoped advisory lock on {@code sourceWalletId} and enforce the
	 * 24-hour outgoing limit; refunds are exempt from the outgoing limit.
	 *
	 * @return {@code true} if inserted, {@code false} if {@code transferId} already existed (idempotent retry)
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean insertInitiated(UUID transferId, UUID sourceWalletId, UUID destinationWalletId, Money amount,
			TransferType type, UUID originalTransferId, long dailyLimitVnd) {
		Objects.requireNonNull(transferId, "transferId must not be null");
		Objects.requireNonNull(sourceWalletId, "sourceWalletId must not be null");
		Objects.requireNonNull(destinationWalletId, "destinationWalletId must not be null");
		Objects.requireNonNull(amount, "amount must not be null");
		Objects.requireNonNull(type, "type must not be null");
		if (amount.currency() != Currency.VND) {
			throw new IllegalArgumentException("amount currency must be VND");
		}
		if (!amount.isPositive()) {
			throw new IllegalArgumentException("amount must be positive");
		}
		if (dailyLimitVnd < 0) {
			throw new IllegalArgumentException("dailyLimitVnd must not be negative");
		}

		if (type == TransferType.STANDARD) {
			jdbc.query(ADVISORY_LOCK, (rs, rowNum) -> null, sourceWalletId.toString());
			if (Boolean.TRUE.equals(jdbc.queryForObject(EXISTS_BY_ID, Boolean.class, transferId))) {
				return false;
			}
			long spent24h = sumOutgoingLast24Hours(sourceWalletId);
			enforceDailyLimit(sourceWalletId, spent24h, amount.amount(), dailyLimitVnd);
		}

		OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
		try {
			int rows = jdbc.update(INSERT_INITIATED, transferId, sourceWalletId, destinationWalletId, amount.amount(),
					amount.currency().name(), type.name(), originalTransferId, TransferStatus.INITIATED.name(),
					now, now);
			return rows == 1;
		} catch (DuplicateKeyException e) {
			return handleDuplicateKeyException(e, originalTransferId);
		}
	}

	public void updateStatus(UUID transferId, TransferStatus status, String failureReason) {
		Objects.requireNonNull(transferId, "transferId must not be null");
		Objects.requireNonNull(status, "status must not be null");
		OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
		jdbc.update(UPDATE_STATUS, status.name(), failureReason, now, transferId);
	}

	public List<StuckSaga> findStuck(Duration olderThan, int limit) {
		Objects.requireNonNull(olderThan, "olderThan must not be null");
		if (olderThan.isNegative()) {
			throw new IllegalArgumentException("olderThan must not be negative");
		}
		if (limit <= 0) {
			throw new IllegalArgumentException("limit must be positive");
		}
		OffsetDateTime cutoff = OffsetDateTime.ofInstant(clock.instant().minus(olderThan), ZoneOffset.UTC);
		return jdbc.query(FIND_STUCK, STUCK_SAGA_ROW_MAPPER, cutoff, limit);
	}

	public int incrementRetry(UUID transferId) {
		Objects.requireNonNull(transferId, "transferId must not be null");
		return jdbc.query(INCREMENT_RETRY, (rs, i) -> rs.getInt("retry_count"), transferId)
				.stream()
				.findFirst()
				.orElseThrow(() -> new TransferNotFoundException(transferId));
	}

	public long sumOutgoingLast24Hours(UUID sourceWalletId) {
		Objects.requireNonNull(sourceWalletId, "sourceWalletId must not be null");
		OffsetDateTime windowStart = OffsetDateTime.ofInstant(clock.instant().minus(DAILY_WINDOW), ZoneOffset.UTC);
		Long total = jdbc.queryForObject(SUM_OUTGOING_24H, Long.class, sourceWalletId, windowStart);
		return total != null ? total : 0L;
	}

	public Optional<TransferSagaSummary> findById(UUID transferId) {
		Objects.requireNonNull(transferId, "transferId must not be null");
		List<TransferSagaSummary> results = jdbc.query(FIND_BY_ID, SUMMARY_ROW_MAPPER, transferId);
		return results.stream().findFirst();
	}

	private static void enforceDailyLimit(UUID sourceWalletId, long spent24h, long requestedAmount,
			long dailyLimitVnd) {
		long projectedTotal;
		try {
			projectedTotal = Math.addExact(spent24h, requestedAmount);
		} catch (ArithmeticException e) {
			throw new TransferPolicyViolationException(
					"Daily outgoing transfer limit exceeded for wallet " + sourceWalletId);
		}
		if (projectedTotal > dailyLimitVnd) {
			throw new TransferPolicyViolationException(
					"Daily outgoing transfer limit of " + dailyLimitVnd + " VND exceeded for wallet "
							+ sourceWalletId + " (spent24h=" + spent24h + ", requested=" + requestedAmount + ")");
		}
	}

	private static boolean handleDuplicateKeyException(DuplicateKeyException e, UUID originalTransferId) {
		Throwable cause = e.getCause();
		while (cause != null) {
			if (cause instanceof PSQLException psqlEx) {
				String constraint = psqlEx.getServerErrorMessage() != null
						? psqlEx.getServerErrorMessage().getConstraint()
						: null;
				if (UQ_ACTIVE_REFUND.equals(constraint)) {
					throw new InvalidTransferStateException(
							"Transfer " + originalTransferId + " already has an active or completed refund");
				}
				if (PK_TRANSFER_ID.equals(constraint)) {
					return false;
				}
				throw e;
			}
			cause = cause.getCause();
		}
		throw e;
	}
}
