package com.lakeserl.mini_banking.transfer.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.transfer.domain.TransferStatus;
import com.lakeserl.mini_banking.transfer.domain.TransferType;
import com.lakeserl.mini_banking.transfer.domain.exceptions.InvalidTransferStateException;
import com.lakeserl.mini_banking.transfer.domain.exceptions.TransferNotFoundException;
import com.lakeserl.mini_banking.transfer.domain.exceptions.TransferPolicyViolationException;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferSagaStateStore.StuckSaga;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferSagaStateStore.TransferSagaSummary;

@PostgresDataTest
class TransferSagaStateStoreTest {

	private static final long DAILY_LIMIT_VND = 200_000_000L;

	@Autowired
	TransferSagaStateStore store;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	Clock clock;

	@BeforeEach
	void clean() {
		jdbc.update("DELETE FROM transfer_saga_state");
	}

	@Test
	void schemaHasExpectedColumnsIndexesAndConstraints() {
		List<String> columns = jdbc.queryForList(
				"SELECT column_name FROM information_schema.columns WHERE table_name = 'transfer_saga_state'",
				String.class);
		assertThat(columns).containsExactlyInAnyOrder(
				"transfer_id", "source_wallet_id", "dest_wallet_id", "amount", "currency",
				"transfer_type", "original_transfer_id", "status", "retry_count", "failure_reason",
				"created_at", "updated_at");

		List<String> indexes = jdbc.queryForList(
				"SELECT indexname FROM pg_indexes WHERE tablename = 'transfer_saga_state'",
				String.class);
		assertThat(indexes).contains(
				"idx_transfer_saga_stuck",
				"idx_transfer_saga_source_created",
				"uq_transfer_active_refund");

		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO transfer_saga_state (transfer_id, source_wallet_id, dest_wallet_id, amount, currency,
				                                 transfer_type, status, retry_count, created_at, updated_at)
				VALUES (?, ?, ?, 0, 'VND', 'STANDARD', 'INITIATED', 0, now(), now())
				""", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO transfer_saga_state (transfer_id, source_wallet_id, dest_wallet_id, amount, currency,
				                                 transfer_type, status, retry_count, created_at, updated_at)
				VALUES (?, ?, ?, 1000, 'VND', 'STANDARD', 'BOGUS', 0, now(), now())
				""", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void sumOutgoingLast24HoursIncludesOnlyRecentStandardActiveOrCompletedTransfers() {
		UUID source = UUID.randomUUID();
		UUID dest = UUID.randomUUID();
		UUID otherSource = UUID.randomUUID();
		Instant now = clock.instant();

		insertRow(UUID.randomUUID(), source, dest, 10_000_000L, TransferType.STANDARD, null,
				TransferStatus.INITIATED, now.minus(Duration.ofHours(1)));
		insertRow(UUID.randomUUID(), source, dest, 20_000_000L, TransferType.STANDARD, null,
				TransferStatus.DEBITED, now.minus(Duration.ofHours(12)));
		insertRow(UUID.randomUUID(), source, dest, 30_000_000L, TransferType.STANDARD, null,
				TransferStatus.COMPLETED, now.minus(Duration.ofHours(23)));

		// Excluded: FAILED, REVERSED, REVERSING
		insertRow(UUID.randomUUID(), source, dest, 40_000_000L, TransferType.STANDARD, null,
				TransferStatus.FAILED, now.minus(Duration.ofHours(2)));
		insertRow(UUID.randomUUID(), source, dest, 50_000_000L, TransferType.STANDARD, null,
				TransferStatus.REVERSED, now.minus(Duration.ofHours(2)));
		insertRow(UUID.randomUUID(), source, dest, 60_000_000L, TransferType.STANDARD, null,
				TransferStatus.REVERSING, now.minus(Duration.ofHours(2)));

		// Excluded: REFUND
		insertRow(UUID.randomUUID(), source, dest, 70_000_000L, TransferType.REFUND, UUID.randomUUID(),
				TransferStatus.COMPLETED, now.minus(Duration.ofHours(1)));

		// Excluded: older than 24h
		insertRow(UUID.randomUUID(), source, dest, 80_000_000L, TransferType.STANDARD, null,
				TransferStatus.COMPLETED, now.minus(Duration.ofHours(25)));

		// Excluded: different source wallet
		insertRow(UUID.randomUUID(), otherSource, dest, 90_000_000L, TransferType.STANDARD, null,
				TransferStatus.COMPLETED, now.minus(Duration.ofHours(1)));

		assertThat(store.sumOutgoingLast24Hours(source)).isEqualTo(60_000_000L);
		assertThat(store.sumOutgoingLast24Hours(UUID.randomUUID())).isZero();
	}

	@Test
	void concurrentInsertInitiatedSerializesViaAdvisoryLockAndEnforces200mDailyLimit() throws Exception {
		UUID source = UUID.randomUUID();
		UUID dest = UUID.randomUUID();
		int threads = 5;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<String>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				UUID transferId = UUID.randomUUID();
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					try {
						Boolean inserted = tx.execute(status -> store.insertInitiated(
								transferId, source, dest, Money.vnd(50_000_000L), TransferType.STANDARD, null,
								DAILY_LIMIT_VND));
						return Boolean.TRUE.equals(inserted) ? "INSERTED" : "DUPLICATE";
					} catch (TransferPolicyViolationException e) {
						return "REJECTED";
					}
				}));
			}
			ready.await(10, TimeUnit.SECONDS);
			go.countDown();

			int insertedCount = 0;
			int rejectedCount = 0;
			for (Future<String> future : futures) {
				String outcome = future.get(30, TimeUnit.SECONDS);
				if ("INSERTED".equals(outcome)) {
					insertedCount++;
				} else if ("REJECTED".equals(outcome)) {
					rejectedCount++;
				}
			}
			assertThat(insertedCount).isEqualTo(4);
			assertThat(rejectedCount).isEqualTo(1);
			assertThat(store.sumOutgoingLast24Hours(source)).isEqualTo(DAILY_LIMIT_VND);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void insertInitiatedReturnsFalseOnDuplicateTransferIdAndRefundIsExemptFromDailyLimit() {
		UUID source = UUID.randomUUID();
		UUID dest = UUID.randomUUID();
		UUID transferId = UUID.randomUUID();

		Boolean first = tx.execute(status -> store.insertInitiated(
				transferId, source, dest, Money.vnd(50_000_000L), TransferType.STANDARD, null, 50_000_000L));
		Boolean retry = tx.execute(status -> {
			boolean inserted = store.insertInitiated(
					transferId, source, dest, Money.vnd(50_000_000L), TransferType.STANDARD, null, 50_000_000L);
			assertThat(store.findById(transferId)).isPresent();
			return inserted;
		});

		assertThat(first).isTrue();
		assertThat(retry).isFalse();

		// Daily limit (50M) is exhausted, yet REFUND is exempt
		UUID refundId = UUID.randomUUID();
		Boolean refundInserted = tx.execute(status -> store.insertInitiated(
				refundId, source, dest, Money.vnd(50_000_000L), TransferType.REFUND, transferId, 50_000_000L));
		assertThat(refundInserted).isTrue();

		Boolean refundRetry = tx.execute(status -> {
			boolean inserted = store.insertInitiated(
					refundId, source, dest, Money.vnd(50_000_000L), TransferType.REFUND, transferId, 50_000_000L);
			assertThat(store.findById(refundId)).isPresent();
			return inserted;
		});
		assertThat(refundRetry).isFalse();

		TransferSagaSummary summary = store.findById(refundId).orElseThrow();
		assertThat(summary.transferId()).isEqualTo(refundId);
		assertThat(summary.sourceWalletId()).isEqualTo(source);
		assertThat(summary.destinationWalletId()).isEqualTo(dest);
		assertThat(summary.amount()).isEqualTo(50_000_000L);
		assertThat(summary.currency()).isEqualTo(Currency.VND);
		assertThat(summary.type()).isEqualTo(TransferType.REFUND);
		assertThat(summary.originalTransferId()).isEqualTo(transferId);
		assertThat(summary.status()).isEqualTo(TransferStatus.INITIATED);
		assertThat(summary.retryCount()).isZero();
		assertThat(summary.failureReason()).isNull();
	}

	@Test
	void findStuckReturnsOnlyOldNonTerminalSagasOrderedOldestFirstAndHonorsLimit() {
		Instant now = clock.instant();
		UUID oldReversing = UUID.randomUUID();
		UUID oldDebited = UUID.randomUUID();
		UUID oldInitiated = UUID.randomUUID();

		insertRow(oldReversing, UUID.randomUUID(), UUID.randomUUID(), 10_000L, TransferType.STANDARD, null,
				TransferStatus.REVERSING, now.minusSeconds(120));
		insertRow(oldDebited, UUID.randomUUID(), UUID.randomUUID(), 10_000L, TransferType.STANDARD, null,
				TransferStatus.DEBITED, now.minusSeconds(90));
		insertRow(oldInitiated, UUID.randomUUID(), UUID.randomUUID(), 10_000L, TransferType.STANDARD, null,
				TransferStatus.INITIATED, now.minusSeconds(60));

		// Old terminal rows must NOT be returned
		insertRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 10_000L, TransferType.STANDARD, null,
				TransferStatus.COMPLETED, now.minusSeconds(120));
		insertRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 10_000L, TransferType.STANDARD, null,
				TransferStatus.FAILED, now.minusSeconds(120));
		insertRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 10_000L, TransferType.STANDARD, null,
				TransferStatus.REVERSED, now.minusSeconds(120));

		// Fresh non-terminal row must NOT be returned
		insertRow(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 10_000L, TransferType.STANDARD, null,
				TransferStatus.INITIATED, now.minusSeconds(5));

		List<StuckSaga> allStuck = store.findStuck(Duration.ofSeconds(30), 10);
		assertThat(allStuck).containsExactly(
				new StuckSaga(oldReversing, TransferStatus.REVERSING, 0),
				new StuckSaga(oldDebited, TransferStatus.DEBITED, 0),
				new StuckSaga(oldInitiated, TransferStatus.INITIATED, 0));

		List<StuckSaga> limited = store.findStuck(Duration.ofSeconds(30), 2);
		assertThat(limited).containsExactly(
				new StuckSaga(oldReversing, TransferStatus.REVERSING, 0),
				new StuckSaga(oldDebited, TransferStatus.DEBITED, 0));
	}

	@Test
	void incrementRetryIncrementsCountWithoutMutatingUpdatedAt() {
		UUID transferId = UUID.randomUUID();
		Instant originalUpdatedAt = clock.instant().minusSeconds(60);
		insertRow(transferId, UUID.randomUUID(), UUID.randomUUID(), 10_000L, TransferType.STANDARD, null,
				TransferStatus.INITIATED, originalUpdatedAt);

		Instant persistedBefore = store.findById(transferId).orElseThrow().updatedAt();

		assertThat(store.incrementRetry(transferId)).isEqualTo(1);
		assertThat(store.incrementRetry(transferId)).isEqualTo(2);

		TransferSagaSummary after = store.findById(transferId).orElseThrow();
		assertThat(after.retryCount()).isEqualTo(2);
		assertThat(after.updatedAt()).isEqualTo(persistedBefore);

		assertThatThrownBy(() -> store.incrementRetry(UUID.randomUUID()))
				.isInstanceOf(TransferNotFoundException.class);
	}

	@Test
	void activeRefundConstraintRejectsSecondActiveRefundButAllowsRetryAfterFailed() {
		UUID source = UUID.randomUUID();
		UUID dest = UUID.randomUUID();
		UUID originalTransferId = UUID.randomUUID();
		UUID firstRefundId = UUID.randomUUID();
		UUID secondRefundId = UUID.randomUUID();

		tx.executeWithoutResult(status -> store.insertInitiated(
				firstRefundId, dest, source, Money.vnd(25_000L), TransferType.REFUND, originalTransferId,
				DAILY_LIMIT_VND));

		assertThatThrownBy(() -> tx.executeWithoutResult(status -> store.insertInitiated(
				secondRefundId, dest, source, Money.vnd(25_000L), TransferType.REFUND, originalTransferId,
				DAILY_LIMIT_VND)))
				.isInstanceOf(InvalidTransferStateException.class)
				.hasMessageContaining(originalTransferId.toString());

		store.updateStatus(firstRefundId, TransferStatus.FAILED, "Insufficient funds");
		TransferSagaSummary failed = store.findById(firstRefundId).orElseThrow();
		assertThat(failed.status()).isEqualTo(TransferStatus.FAILED);
		assertThat(failed.failureReason()).isEqualTo("Insufficient funds");

		Boolean secondInserted = tx.execute(status -> store.insertInitiated(
				secondRefundId, dest, source, Money.vnd(25_000L), TransferType.REFUND, originalTransferId,
				DAILY_LIMIT_VND));
		assertThat(secondInserted).isTrue();
	}

	private void insertRow(UUID transferId, UUID source, UUID dest, long amount, TransferType type,
			UUID originalTransferId, TransferStatus status, Instant timestamp) {
		OffsetDateTime odt = OffsetDateTime.ofInstant(timestamp, ZoneOffset.UTC);
		jdbc.update("""
				INSERT INTO transfer_saga_state (
				    transfer_id, source_wallet_id, dest_wallet_id, amount, currency,
				    transfer_type, original_transfer_id, status, retry_count, failure_reason,
				    created_at, updated_at
				) VALUES (?, ?, ?, ?, 'VND', ?, ?, ?, 0, NULL, ?, ?)
				""", transferId, source, dest, amount, type.name(), originalTransferId, status.name(), odt, odt);
	}
}
