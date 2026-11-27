package com.lakeserl.mini_banking.transfer.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.transfer.domain.TransferStatus;
import com.lakeserl.mini_banking.transfer.domain.TransferType;
import com.lakeserl.mini_banking.transfer.domain.exceptions.InvalidTransferStateException;
import com.lakeserl.mini_banking.transfer.domain.exceptions.TransferPolicyViolationException;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferSagaStateStore.StuckSaga;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferSagaStateStore.TransferSagaSummary;

class TransferSagaStateStoreUnitTest {

	private static final Instant FIXED_NOW = Instant.parse("2026-11-19T08:00:00Z");
	private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

	private JdbcTemplate jdbc;
	private TransferSagaStateStore store;

	@BeforeEach
	void setUp() {
		jdbc = mock(JdbcTemplate.class);
		store = new TransferSagaStateStore(jdbc, FIXED_CLOCK);
	}

	@SuppressWarnings("unchecked")
	@Test
	void insertInitiatedStandardAcquiresAdvisoryLockChecksExistsSumsAndInsertsWithOnConflictDoNothing() {
		UUID transferId = UUID.randomUUID();
		UUID source = UUID.randomUUID();
		UUID dest = UUID.randomUUID();
		OffsetDateTime expectedNow = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);
		OffsetDateTime expectedWindowStart = OffsetDateTime.ofInstant(FIXED_NOW.minus(Duration.ofHours(24)),
				ZoneOffset.UTC);

		when(jdbc.queryForObject(
				eq("SELECT EXISTS (SELECT 1 FROM transfer_saga_state WHERE transfer_id = ?)"),
				eq(Boolean.class),
				eq(transferId))).thenReturn(false);
		when(jdbc.queryForObject(anyString(), eq(Long.class), eq(source), eq(expectedWindowStart)))
				.thenReturn(150_000_000L);
		ArgumentCaptor<String> insertSqlCaptor = ArgumentCaptor.forClass(String.class);
		when(jdbc.update(insertSqlCaptor.capture(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(1);

		boolean inserted = store.insertInitiated(transferId, source, dest, Money.vnd(50_000_000L),
				TransferType.STANDARD, null, 200_000_000L);

		assertThat(inserted).isTrue();
		assertThat(insertSqlCaptor.getValue()).contains("ON CONFLICT (transfer_id) DO NOTHING");
		InOrder order = inOrder(jdbc);
		order.verify(jdbc).query(
				eq("SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))"),
				any(RowMapper.class),
				eq(source.toString()));
		order.verify(jdbc).queryForObject(
				eq("SELECT EXISTS (SELECT 1 FROM transfer_saga_state WHERE transfer_id = ?)"),
				eq(Boolean.class),
				eq(transferId));
		order.verify(jdbc).queryForObject(
				contains("COALESCE(SUM(amount), 0)"),
				eq(Long.class),
				eq(source),
				eq(expectedWindowStart));
		order.verify(jdbc).update(
				contains("ON CONFLICT (transfer_id) DO NOTHING"),
				eq(transferId),
				eq(source),
				eq(dest),
				eq(50_000_000L),
				eq("VND"),
				eq("STANDARD"),
				eq(null),
				eq("INITIATED"),
				eq(expectedNow),
				eq(expectedNow));
	}

	@SuppressWarnings("unchecked")
	@Test
	void insertInitiatedStandardReturnsFalseEarlyWhenTransferAlreadyExistsEvenAtDailyLimit() {
		UUID transferId = UUID.randomUUID();
		UUID source = UUID.randomUUID();
		when(jdbc.queryForObject(
				eq("SELECT EXISTS (SELECT 1 FROM transfer_saga_state WHERE transfer_id = ?)"),
				eq(Boolean.class),
				eq(transferId))).thenReturn(true);
		when(jdbc.queryForObject(anyString(), eq(Long.class), eq(source), any(OffsetDateTime.class)))
				.thenReturn(50_000_000L);

		boolean inserted = store.insertInitiated(transferId, source, UUID.randomUUID(),
				Money.vnd(50_000_000L), TransferType.STANDARD, null, 50_000_000L);

		assertThat(inserted).isFalse();
		verify(jdbc).query(
				eq("SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))"),
				any(RowMapper.class),
				eq(source.toString()));
		verify(jdbc, never()).queryForObject(anyString(), eq(Long.class), any(), any());
		verify(jdbc, never()).update(anyString(), any(Object[].class));
	}

	@SuppressWarnings("unchecked")
	@Test
	void insertInitiatedStandardRejectsWhenDailyLimitExceededWithoutInserting() {
		UUID source = UUID.randomUUID();
		when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(UUID.class))).thenReturn(false);
		when(jdbc.queryForObject(anyString(), eq(Long.class), eq(source), any(OffsetDateTime.class)))
				.thenReturn(150_000_001L);

		assertThatThrownBy(() -> store.insertInitiated(UUID.randomUUID(), source, UUID.randomUUID(),
				Money.vnd(50_000_000L), TransferType.STANDARD, null, 200_000_000L))
				.isInstanceOf(TransferPolicyViolationException.class);

		verify(jdbc).query(eq("SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))"), any(RowMapper.class),
				eq(source.toString()));
		verify(jdbc, never()).update(anyString(), any(Object[].class));
	}

	@Test
	void insertInitiatedStandardProtectsAgainstLongOverflowWithMathAddExact() {
		UUID source = UUID.randomUUID();
		when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(UUID.class))).thenReturn(false);
		when(jdbc.queryForObject(anyString(), eq(Long.class), eq(source), any(OffsetDateTime.class)))
				.thenReturn(Long.MAX_VALUE - 5L);

		assertThatThrownBy(() -> store.insertInitiated(UUID.randomUUID(), source, UUID.randomUUID(),
				Money.vnd(100L), TransferType.STANDARD, null, Long.MAX_VALUE))
				.isInstanceOf(TransferPolicyViolationException.class);

		verify(jdbc, never()).update(anyString(), any(Object[].class));
	}

	@SuppressWarnings("unchecked")
	@Test
	void insertInitiatedRefundIsExemptFromDailyLimitSkipsAdvisoryLockAndChecksExistsBeforeInsert() {
		UUID transferId = UUID.randomUUID();
		UUID source = UUID.randomUUID();
		UUID dest = UUID.randomUUID();
		UUID originalTransferId = UUID.randomUUID();
		OffsetDateTime expectedNow = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);

		when(jdbc.queryForObject(
				eq("SELECT EXISTS (SELECT 1 FROM transfer_saga_state WHERE transfer_id = ?)"),
				eq(Boolean.class),
				eq(transferId))).thenReturn(false);
		when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(1);

		boolean inserted = store.insertInitiated(transferId, source, dest, Money.vnd(50_000_000L),
				TransferType.REFUND, originalTransferId, 0L);

		assertThat(inserted).isTrue();
		verify(jdbc, never()).query(anyString(), any(RowMapper.class), any());
		verify(jdbc, never()).queryForObject(anyString(), eq(Long.class), any(), any());
		InOrder order = inOrder(jdbc);
		order.verify(jdbc).queryForObject(
				eq("SELECT EXISTS (SELECT 1 FROM transfer_saga_state WHERE transfer_id = ?)"),
				eq(Boolean.class),
				eq(transferId));
		order.verify(jdbc).update(
				contains("ON CONFLICT (transfer_id) DO NOTHING"),
				eq(transferId),
				eq(source),
				eq(dest),
				eq(50_000_000L),
				eq("VND"),
				eq("REFUND"),
				eq(originalTransferId),
				eq("INITIATED"),
				eq(expectedNow),
				eq(expectedNow));
	}

	@SuppressWarnings("unchecked")
	@Test
	void insertInitiatedRefundReturnsFalseEarlyWhenTransferAlreadyExistsWithoutCallingInsert() {
		UUID transferId = UUID.randomUUID();
		UUID originalTransferId = UUID.randomUUID();
		when(jdbc.queryForObject(
				eq("SELECT EXISTS (SELECT 1 FROM transfer_saga_state WHERE transfer_id = ?)"),
				eq(Boolean.class),
				eq(transferId))).thenReturn(true);

		boolean inserted = store.insertInitiated(transferId, UUID.randomUUID(), UUID.randomUUID(),
				Money.vnd(50_000_000L), TransferType.REFUND, originalTransferId, 0L);

		assertThat(inserted).isFalse();
		verify(jdbc, never()).query(anyString(), any(RowMapper.class), any());
		verify(jdbc, never()).update(anyString(), any(Object[].class));
	}

	@Test
	void insertInitiatedMapsActiveRefundConstraintToInvalidTransferStateException() {
		UUID transferId = UUID.randomUUID();
		UUID originalTransferId = UUID.randomUUID();
		DuplicateKeyException dup = duplicateKeyForConstraint("uq_transfer_active_refund");
		when(jdbc.queryForObject(
				eq("SELECT EXISTS (SELECT 1 FROM transfer_saga_state WHERE transfer_id = ?)"),
				eq(Boolean.class),
				eq(transferId))).thenReturn(false);
		when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
				.thenThrow(dup);

		assertThatThrownBy(() -> store.insertInitiated(transferId, UUID.randomUUID(), UUID.randomUUID(),
				Money.vnd(10_000L), TransferType.REFUND, originalTransferId, 200_000_000L))
				.isInstanceOf(InvalidTransferStateException.class)
				.hasMessage("Transfer " + originalTransferId + " already has an active or completed refund");
	}

	@Test
	void insertInitiatedReturnsFalseWhenOnConflictDoNothingUpdatesZeroRows() {
		when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(UUID.class))).thenReturn(false);
		when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(0L);
		when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(0);

		boolean inserted = store.insertInitiated(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				Money.vnd(10_000L), TransferType.STANDARD, null, 200_000_000L);

		assertThat(inserted).isFalse();
	}

	@Test
	void insertInitiatedRethrowsUnrecognizedDuplicateKeyException() {
		DuplicateKeyException dup = duplicateKeyForConstraint("some_other_constraint");
		when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(UUID.class))).thenReturn(false);
		when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(0L);
		when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
				.thenThrow(dup);

		assertThatThrownBy(() -> store.insertInitiated(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				Money.vnd(10_000L), TransferType.STANDARD, null, 200_000_000L))
				.isSameAs(dup);
	}

	@Test
	void insertInitiatedRejectsNonVndCurrencyAndDeclaresMandatoryTransactionPropagation() throws Exception {
		assertThatThrownBy(() -> store.insertInitiated(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				new Money(100L, Currency.USD), TransferType.STANDARD, null, 200_000_000L))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("VND");

		Method method = TransferSagaStateStore.class.getMethod("insertInitiated",
				UUID.class, UUID.class, UUID.class, Money.class, TransferType.class, UUID.class, long.class);
		Transactional txAnnotation = method.getAnnotation(Transactional.class);
		assertThat(txAnnotation).isNotNull();
		assertThat(txAnnotation.propagation()).isEqualTo(Propagation.MANDATORY);
	}

	@Test
	void updateStatusUpdatesStatusFailureReasonAndClockTimestamp() {
		UUID transferId = UUID.randomUUID();
		OffsetDateTime expectedNow = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);

		store.updateStatus(transferId, TransferStatus.FAILED, "Wallet locked");

		verify(jdbc).update(
				contains("UPDATE transfer_saga_state"),
				eq("FAILED"),
				eq("Wallet locked"),
				eq(expectedNow),
				eq(transferId));
	}

	@SuppressWarnings("unchecked")
	@Test
	void incrementRetryUpdatesUpdatedAtGuardsNonTerminalStatusesAndReturnsEmptyWhenMissingOrTerminal() throws Exception {
		UUID transferId = UUID.randomUUID();
		OffsetDateTime expectedNow = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);
		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<RowMapper<Integer>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
		when(jdbc.query(sqlCaptor.capture(), mapperCaptor.capture(), eq(expectedNow), eq(transferId)))
				.thenReturn(List.of(3));

		OptionalInt retryCount = store.incrementRetry(transferId);

		assertThat(retryCount).isEqualTo(OptionalInt.of(3));
		String sql = sqlCaptor.getValue();
		assertThat(sql).contains("retry_count = retry_count + 1");
		assertThat(sql).contains("updated_at = ?");
		assertThat(sql).contains("RETURNING retry_count");
		for (TransferStatus status : TransferStatus.values()) {
			if (!status.isTerminal()) {
				assertThat(sql).contains("'" + status.name() + "'");
			} else {
				assertThat(sql).doesNotContain("'" + status.name() + "'");
			}
		}

		ResultSet rs = mock(ResultSet.class);
		when(rs.getInt("retry_count")).thenReturn(3);
		assertThat(mapperCaptor.getValue().mapRow(rs, 0)).isEqualTo(3);

		UUID missingOrTerminalId = UUID.randomUUID();
		when(jdbc.query(anyString(), any(RowMapper.class), eq(expectedNow), eq(missingOrTerminalId)))
				.thenReturn(List.of());
		assertThat(store.incrementRetry(missingOrTerminalId)).isEmpty();
	}

	@SuppressWarnings("unchecked")
	@Test
	void findStuckPassesCutoffTimestampAndLimitAndMapsStuckSaga() throws Exception {
		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<RowMapper<StuckSaga>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
		OffsetDateTime expectedCutoff = OffsetDateTime.ofInstant(FIXED_NOW.minus(Duration.ofSeconds(30)),
				ZoneOffset.UTC);

		UUID expectedId = UUID.randomUUID();
		when(jdbc.query(sqlCaptor.capture(), mapperCaptor.capture(), eq(expectedCutoff), eq(25)))
				.thenReturn(List.of(new StuckSaga(expectedId, TransferStatus.DEBITED, 2)));

		List<StuckSaga> result = store.findStuck(Duration.ofSeconds(30), 25);

		assertThat(result).containsExactly(new StuckSaga(expectedId, TransferStatus.DEBITED, 2));
		String sql = sqlCaptor.getValue();
		assertThat(sql).contains("status IN ('INITIATED', 'DEBITED', 'REVERSING')");
		assertThat(sql).contains("updated_at <= ?");
		assertThat(sql).contains("ORDER BY updated_at ASC LIMIT ?");

		ResultSet rs = mock(ResultSet.class);
		when(rs.getObject("transfer_id", UUID.class)).thenReturn(expectedId);
		when(rs.getString("status")).thenReturn("DEBITED");
		when(rs.getInt("retry_count")).thenReturn(2);

		StuckSaga mapped = mapperCaptor.getValue().mapRow(rs, 0);
		assertThat(mapped).isEqualTo(new StuckSaga(expectedId, TransferStatus.DEBITED, 2));
	}

	@SuppressWarnings("unchecked")
	@Test
	void findByIdMapsTransferSagaSummaryAndReturnsEmptyWhenAbsent() throws Exception {
		UUID transferId = UUID.randomUUID();
		UUID source = UUID.randomUUID();
		UUID dest = UUID.randomUUID();
		UUID originalId = UUID.randomUUID();
		OffsetDateTime created = OffsetDateTime.ofInstant(FIXED_NOW.minusSeconds(10), ZoneOffset.UTC);
		OffsetDateTime updated = OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC);

		ArgumentCaptor<RowMapper<TransferSagaSummary>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
		when(jdbc.query(anyString(), mapperCaptor.capture(), eq(transferId))).thenReturn(List.of());

		assertThat(store.findById(transferId)).isEmpty();

		ResultSet rs = mock(ResultSet.class);
		when(rs.getObject("transfer_id", UUID.class)).thenReturn(transferId);
		when(rs.getObject("source_wallet_id", UUID.class)).thenReturn(source);
		when(rs.getObject("dest_wallet_id", UUID.class)).thenReturn(dest);
		when(rs.getLong("amount")).thenReturn(500_000L);
		when(rs.getString("currency")).thenReturn("VND");
		when(rs.getString("transfer_type")).thenReturn("REFUND");
		when(rs.getObject("original_transfer_id", UUID.class)).thenReturn(originalId);
		when(rs.getString("status")).thenReturn("COMPLETED");
		when(rs.getInt("retry_count")).thenReturn(1);
		when(rs.getString("failure_reason")).thenReturn(null);
		when(rs.getObject("created_at", OffsetDateTime.class)).thenReturn(created);
		when(rs.getObject("updated_at", OffsetDateTime.class)).thenReturn(updated);

		TransferSagaSummary mapped = mapperCaptor.getValue().mapRow(rs, 0);
		assertThat(mapped).isEqualTo(new TransferSagaSummary(
				transferId, source, dest, 500_000L, Currency.VND, TransferType.REFUND, originalId,
				TransferStatus.COMPLETED, 1, null, created.toInstant(), updated.toInstant()));
		assertThat(Optional.ofNullable(mapped)).isPresent();
	}

	@Test
	void sumOutgoingLast24HoursHandlesNullAsZeroAndFiltersStandardStatuses() {
		UUID source = UUID.randomUUID();
		OffsetDateTime expectedWindowStart = OffsetDateTime.ofInstant(FIXED_NOW.minus(Duration.ofHours(24)),
				ZoneOffset.UTC);
		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		when(jdbc.queryForObject(sqlCaptor.capture(), eq(Long.class), eq(source), eq(expectedWindowStart)))
				.thenReturn(null);

		long total = store.sumOutgoingLast24Hours(source);

		assertThat(total).isZero();
		String sql = sqlCaptor.getValue();
		assertThat(sql).contains("COALESCE(SUM(amount), 0)");
		assertThat(sql).contains("transfer_type = 'STANDARD'");
		assertThat(sql).contains("status IN ('INITIATED', 'DEBITED', 'COMPLETED')");
		assertThat(sql).contains("created_at >= ?");
	}

	@Test
	void v4MigrationResourceExistsWithRequiredColumnsConstraintsAndIndexes() throws Exception {
		ClassPathResource resource = new ClassPathResource("db/migration/V4__transfer_saga_state.sql");
		assertThat(resource.exists()).isTrue();

		String sql = resource.getContentAsString(StandardCharsets.UTF_8);
		assertThat(sql).contains("transfer_saga_state");
		assertThat(sql).contains("transfer_id", "source_wallet_id", "dest_wallet_id", "amount", "currency",
				"transfer_type", "original_transfer_id", "status", "retry_count", "failure_reason",
				"created_at", "updated_at");
		assertThat(sql).contains("CHECK (amount > 0)");
		assertThat(sql).contains("CHECK (transfer_type IN ('STANDARD', 'REFUND'))");
		assertThat(sql).contains("CHECK (status IN ('INITIATED', 'DEBITED', 'COMPLETED', 'FAILED', 'REVERSING', 'REVERSED'))");
		assertThat(sql).contains("CHECK (retry_count >= 0)");
		assertThat(sql).contains("idx_transfer_saga_stuck");
		assertThat(sql).contains("WHERE status IN ('INITIATED', 'DEBITED', 'REVERSING')");
		assertThat(sql).contains("idx_transfer_saga_source_created");
		assertThat(sql).contains("uq_transfer_active_refund");
		assertThat(sql).contains("WHERE original_transfer_id IS NOT NULL AND status NOT IN ('FAILED', 'REVERSED')");
	}

	private static DuplicateKeyException duplicateKeyForConstraint(String constraintName) {
		ServerErrorMessage errorMessage = new ServerErrorMessage(
				"SERROR\0C23505\0Mduplicate key value violates unique constraint\0ttransfer_saga_state\0n"
						+ constraintName + "\0");
		PSQLException psqlException = new PSQLException(errorMessage);
		return new DuplicateKeyException("duplicate key", new RuntimeException("wrapped", psqlException));
	}
}
