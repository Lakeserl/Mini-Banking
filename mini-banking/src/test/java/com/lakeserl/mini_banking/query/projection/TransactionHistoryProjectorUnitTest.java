package com.lakeserl.mini_banking.query.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.lakeserl.mini_banking.deposit.domain.events.DepositBankVerified;
import com.lakeserl.mini_banking.deposit.domain.events.DepositConfirmed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositFailed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositRequested;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.transfer.domain.TransferType;
import com.lakeserl.mini_banking.transfer.domain.events.TransferCompensated;
import com.lakeserl.mini_banking.transfer.domain.events.TransferCompensationStarted;
import com.lakeserl.mini_banking.transfer.domain.events.TransferCompleted;
import com.lakeserl.mini_banking.transfer.domain.events.TransferCreditCompleted;
import com.lakeserl.mini_banking.transfer.domain.events.TransferDebitCompleted;
import com.lakeserl.mini_banking.transfer.domain.events.TransferFailed;
import com.lakeserl.mini_banking.transfer.domain.events.TransferInitiated;
import com.lakeserl.mini_banking.wallet.domain.events.WalletOpened;
import com.lakeserl.mini_banking.withdrawal.domain.events.WithdrawalConfirmed;
import com.lakeserl.mini_banking.withdrawal.domain.events.WithdrawalFailed;
import com.lakeserl.mini_banking.withdrawal.domain.events.WithdrawalRequested;

class TransactionHistoryProjectorUnitTest {

	record TxRow(UUID txId, UUID walletId, String txType, String direction, long amount, String currency,
			String status, UUID counterpartWalletId, String counterpartLabel, String bankReference,
			UUID originalTransferId, String failureReason, OffsetDateTime occurredAt, OffsetDateTime updatedAt) {
	}

	record DailyRow(LocalDate date, String currency, long deposit, long withdrawal, long transfer, long refund,
			long txCount) {
	}

	private JdbcTemplate jdbc;
	private TransactionTemplate tx;
	private TransactionHistoryProjector projector;
	private final Set<String> processed = new HashSet<>();
	private final Map<String, TxRow> txRows = new HashMap<>();
	private final Map<String, DailyRow> dailyRows = new HashMap<>();

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		jdbc = mock(JdbcTemplate.class);
		tx = mock(TransactionTemplate.class);
		when(tx.execute(any(TransactionCallback.class))).thenAnswer(inv -> {
			TransactionCallback<Object> cb = inv.getArgument(0);
			Set<String> processedSnapshot = new HashSet<>(processed);
			Map<String, TxRow> txSnapshot = new HashMap<>(txRows);
			Map<String, DailyRow> dailySnapshot = new HashMap<>(dailyRows);
			try {
				return cb.doInTransaction(mock(TransactionStatus.class));
			} catch (RuntimeException ex) {
				processed.clear();
				processed.addAll(processedSnapshot);
				txRows.clear();
				txRows.putAll(txSnapshot);
				dailyRows.clear();
				dailyRows.putAll(dailySnapshot);
				throw ex;
			}
		});

		when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
			String sql = inv.getArgument(0);
			Object[] args = inv.getArguments();
			if (sql.contains("INSERT INTO processed_events")) {
				return processed.add(args[1] + ":" + args[2]) ? 1 : 0;
			}
			if (sql.contains("INSERT INTO transaction_history")) {
				UUID txId = (UUID) args[1];
				UUID walletId = (UUID) args[2];
				TxRow row = new TxRow(txId, walletId, (String) args[3], (String) args[4], (Long) args[5],
						(String) args[6], (String) args[7], (UUID) args[8], (String) args[9], (String) args[10],
						(UUID) args[11], null, (OffsetDateTime) args[12], (OffsetDateTime) args[13]);
				txRows.putIfAbsent(txId + ":" + walletId, row);
				return 1;
			}
			if (sql.contains("UPDATE transaction_history")) {
				if (sql.contains("bank_reference = COALESCE")) {
					String status = (String) args[1];
					String bankRef = (String) args[2];
					String reason = (String) args[3];
					OffsetDateTime updatedAt = (OffsetDateTime) args[4];
					UUID txId = (UUID) args[5];
					int count = 0;
					for (Map.Entry<String, TxRow> e : txRows.entrySet()) {
						TxRow r = e.getValue();
						if (r.txId().equals(txId)) {
							e.setValue(new TxRow(r.txId(), r.walletId(), r.txType(), r.direction(), r.amount(),
									r.currency(), status, r.counterpartWalletId(), r.counterpartLabel(),
									bankRef != null ? bankRef : r.bankReference(), r.originalTransferId(),
									reason != null ? reason : r.failureReason(), r.occurredAt(), updatedAt));
							count++;
						}
					}
					return count;
				}
			}
			if (sql.contains("INSERT INTO daily_reports")) {
				LocalDate date = args[1] instanceof Date d ? d.toLocalDate() : (LocalDate) args[1];
				String currency = (String) args[2];
				long dep = (Long) args[3];
				long wdr = (Long) args[4];
				long trf = (Long) args[5];
				long ref = (Long) args[6];
				String key = date + ":" + currency;
				DailyRow cur = dailyRows.getOrDefault(key, new DailyRow(date, currency, 0, 0, 0, 0, 0));
				dailyRows.put(key, new DailyRow(date, currency, cur.deposit() + dep, cur.withdrawal() + wdr,
						cur.transfer() + trf, cur.refund() + ref, cur.txCount() + 1));
				return 1;
			}
			throw new IllegalStateException("Unexpected SQL: " + sql);
		});

		when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(inv -> {
			UUID txId = (UUID) inv.getArguments()[2];
			return txRows.values().stream()
					.filter(r -> r.txId().equals(txId))
					.findFirst()
					.map(r -> List.of(new TransactionHistoryProjector.TxMeta(r.txType(), r.amount(), r.currency())))
					.orElse(List.of());
		});

		projector = new TransactionHistoryProjector(jdbc, tx);
	}

	@Test
	void depositLifecycleUsesAsiaHoChiMinhDateBucketForDailyReport() {
		UUID depositId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		// 17:30 UTC on 2026-11-21 is 00:30 on 2026-11-22 in Asia/Ho_Chi_Minh (UTC+7)
		Instant t1 = Instant.parse("2026-11-21T17:30:00Z");
		Instant t2 = Instant.parse("2026-11-21T17:31:00Z");

		projector.project(UUID.randomUUID(),
				new DepositRequested(depositId, walletId, Money.vnd(1_000_000), "BANK-REF-1"), t1);
		TxRow pending = txRows.get(depositId + ":" + walletId);
		assertThat(pending.status()).isEqualTo("PENDING");
		assertThat(pending.txType()).isEqualTo("DEPOSIT");
		assertThat(pending.direction()).isEqualTo("IN");
		assertThat(pending.bankReference()).isEqualTo("BANK-REF-1");

		projector.project(UUID.randomUUID(), new DepositConfirmed(depositId), t2);
		TxRow confirmed = txRows.get(depositId + ":" + walletId);
		assertThat(confirmed.status()).isEqualTo("CONFIRMED");
		assertThat(confirmed.occurredAt()).isEqualTo(OffsetDateTime.ofInstant(t1, ZoneOffset.UTC));
		assertThat(confirmed.updatedAt()).isEqualTo(OffsetDateTime.ofInstant(t2, ZoneOffset.UTC));

		DailyRow report = dailyRows.get("2026-11-22:VND");
		assertThat(report).isNotNull();
		assertThat(report.deposit()).isEqualTo(1_000_000L);
		assertThat(report.txCount()).isEqualTo(1L);
	}

	@Test
	void withdrawalMasksBankAccountAndRecordsFailureWithoutDailyReport() {
		UUID withdrawalId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Instant t1 = Instant.parse("2026-11-21T02:00:00Z");
		Instant t2 = Instant.parse("2026-11-21T02:01:00Z");

		projector.project(UUID.randomUUID(),
				new WithdrawalRequested(withdrawalId, walletId, Money.vnd(250_000), "97043612345678"), t1);
		TxRow row = txRows.get(withdrawalId + ":" + walletId);
		assertThat(row.counterpartLabel()).isEqualTo("****5678");
		assertThat(row.direction()).isEqualTo("OUT");

		projector.project(UUID.randomUUID(), new WithdrawalFailed(withdrawalId, "bank rejected"), t2);
		TxRow failed = txRows.get(withdrawalId + ":" + walletId);
		assertThat(failed.status()).isEqualTo("FAILED");
		assertThat(failed.failureReason()).isEqualTo("bank rejected");
		assertThat(dailyRows).isEmpty();
	}

	@Test
	void withdrawalConfirmedSetsBankReferenceAndIncrementsDailyWithdrawal() {
		UUID withdrawalId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Instant t1 = Instant.parse("2026-11-21T03:00:00Z");
		Instant t2 = Instant.parse("2026-11-21T03:02:00Z");

		projector.project(UUID.randomUUID(),
				new WithdrawalRequested(withdrawalId, walletId, Money.vnd(300_000), "12345678"), t1);
		projector.project(UUID.randomUUID(), new WithdrawalConfirmed(withdrawalId, "PAY-001"), t2);

		TxRow confirmed = txRows.get(withdrawalId + ":" + walletId);
		assertThat(confirmed.status()).isEqualTo("CONFIRMED");
		assertThat(confirmed.bankReference()).isEqualTo("PAY-001");
		assertThat(dailyRows.get("2026-11-21:VND").withdrawal()).isEqualTo(300_000L);
	}

	@Test
	void transferStandardCreatesTwoRowsPreservesOccurredAtAndUpdatesDailyTransfer() {
		UUID transferId = UUID.randomUUID();
		UUID src = UUID.randomUUID();
		UUID dst = UUID.randomUUID();
		Instant t1 = Instant.parse("2026-11-21T04:00:00Z");
		Instant t2 = Instant.parse("2026-11-21T04:00:01Z");
		Instant t3 = Instant.parse("2026-11-21T04:00:02Z");

		projector.project(UUID.randomUUID(),
				new TransferInitiated(transferId, src, dst, Money.vnd(500_000), TransferType.STANDARD, null), t1);
		assertThat(txRows).containsKeys(transferId + ":" + src, transferId + ":" + dst);
		assertThat(txRows.get(transferId + ":" + src).direction()).isEqualTo("OUT");
		assertThat(txRows.get(transferId + ":" + src).counterpartWalletId()).isEqualTo(dst);
		assertThat(txRows.get(transferId + ":" + dst).direction()).isEqualTo("IN");
		assertThat(txRows.get(transferId + ":" + dst).counterpartWalletId()).isEqualTo(src);

		projector.project(UUID.randomUUID(), new TransferDebitCompleted(transferId), t2);
		assertThat(txRows.get(transferId + ":" + src).status()).isEqualTo("DEBITED");

		projector.project(UUID.randomUUID(), new TransferCreditCompleted(transferId), t3);
		projector.project(UUID.randomUUID(), new TransferCompleted(transferId), t3);
		assertThat(txRows.get(transferId + ":" + src).status()).isEqualTo("COMPLETED");
		assertThat(txRows.get(transferId + ":" + dst).status()).isEqualTo("COMPLETED");
		assertThat(txRows.get(transferId + ":" + src).occurredAt())
				.isEqualTo(OffsetDateTime.ofInstant(t1, ZoneOffset.UTC));
		assertThat(dailyRows.get("2026-11-21:VND").transfer()).isEqualTo(500_000L);
	}

	@Test
	void transferRefundAndCompensationLifecycle() {
		UUID origId = UUID.randomUUID();
		UUID refundId = UUID.randomUUID();
		UUID src = UUID.randomUUID();
		UUID dst = UUID.randomUUID();
		Instant t1 = Instant.parse("2026-11-21T05:00:00Z");

		projector.project(UUID.randomUUID(),
				new TransferInitiated(refundId, dst, src, Money.vnd(150_000), TransferType.REFUND, origId), t1);
		assertThat(txRows.get(refundId + ":" + dst).txType()).isEqualTo("REFUND");
		assertThat(txRows.get(refundId + ":" + dst).originalTransferId()).isEqualTo(origId);

		projector.project(UUID.randomUUID(), new TransferCompleted(refundId), t1);
		assertThat(dailyRows.get("2026-11-21:VND").refund()).isEqualTo(150_000L);

		UUID compId = UUID.randomUUID();
		projector.project(UUID.randomUUID(),
				new TransferInitiated(compId, src, dst, Money.vnd(200_000), TransferType.STANDARD, null), t1);
		projector.project(UUID.randomUUID(), new TransferCompensationStarted(compId, "dest locked"), t1);
		assertThat(txRows.get(compId + ":" + src).status()).isEqualTo("REVERSING");
		assertThat(txRows.get(compId + ":" + src).failureReason()).isEqualTo("dest locked");

		projector.project(UUID.randomUUID(), new TransferCompensated(compId), t1);
		assertThat(txRows.get(compId + ":" + src).status()).isEqualTo("REVERSED");

		UUID failId = UUID.randomUUID();
		projector.project(UUID.randomUUID(),
				new TransferInitiated(failId, src, dst, Money.vnd(50_000), TransferType.STANDARD, null), t1);
		projector.project(UUID.randomUUID(), new TransferFailed(failId, "insufficient funds"), t1);
		assertThat(txRows.get(failId + ":" + src).status()).isEqualTo("FAILED");
		assertThat(txRows.get(failId + ":" + src).failureReason()).isEqualTo("insufficient funds");
	}

	@Test
	void duplicateEventIdIsIgnoredAndUnsupportedEventRejectedBeforeTx() {
		UUID depositId = UUID.randomUUID();
		UUID eventId = UUID.randomUUID();
		Instant t1 = Instant.parse("2026-11-21T06:00:00Z");
		DepositRequested ev = new DepositRequested(depositId, UUID.randomUUID(), Money.vnd(100_000), "REF-DUP");

		assertThat(projector.project(eventId, ev, t1)).isTrue();
		assertThat(projector.project(eventId, ev, t1)).isFalse();

		JdbcTemplate freshJdbc = mock(JdbcTemplate.class);
		TransactionTemplate freshTx = mock(TransactionTemplate.class);
		TransactionHistoryProjector fresh = new TransactionHistoryProjector(freshJdbc, freshTx);
		assertThatThrownBy(() -> fresh.project(UUID.randomUUID(),
				new WalletOpened(UUID.randomUUID(), UUID.randomUUID(), Currency.VND), t1))
				.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(freshTx);
	}

	@Test
	void depositFailedRecordsReasonWithoutDailyReport() {
		UUID depositId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Instant t1 = Instant.parse("2026-11-21T07:00:00Z");
		projector.project(UUID.randomUUID(),
				new DepositRequested(depositId, walletId, Money.vnd(100_000), "REF-FAIL"), t1);
		projector.project(UUID.randomUUID(), new DepositFailed(depositId, "card declined"), t1);

		assertThat(txRows.get(depositId + ":" + walletId).status()).isEqualTo("FAILED");
		assertThat(txRows.get(depositId + ":" + walletId).failureReason()).isEqualTo("card declined");
		assertThat(dailyRows).isEmpty();
	}

	@Test
	void statusUpdateForMissingTransactionThrowsAndRollsBackProcessedEvent() {
		UUID missingDepositId = UUID.randomUUID();
		UUID eventId = UUID.randomUUID();
		Instant t1 = Instant.parse("2026-11-21T07:05:00Z");

		assertThatThrownBy(() -> projector.project(eventId, new DepositConfirmed(missingDepositId), t1))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(missingDepositId.toString());
		assertThat(processed).doesNotContain(TransactionHistoryProjector.DEFAULT_CONSUMER_GROUP + ":" + eventId);
		assertThat(dailyRows).isEmpty();
	}

	@Test
	void depositBankVerifiedIsIdempotentNoOpKeepingStatusPendingUntilConfirmed() {
		UUID depositId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		UUID verifiedEventId = UUID.randomUUID();
		Instant t1 = Instant.parse("2026-11-21T08:00:00Z");
		Instant t2 = Instant.parse("2026-11-21T08:00:05Z");
		Instant t3 = Instant.parse("2026-11-21T08:00:10Z");
		String legacy150 = "LEGACY-" + "X".repeat(143);

		assertThat(projector.project(UUID.randomUUID(),
				new DepositRequested(depositId, walletId, Money.vnd(500_000), legacy150), t1)).isTrue();
		assertThat(projector.project(verifiedEventId, new DepositBankVerified(depositId), t2)).isTrue();
		assertThat(projector.project(verifiedEventId, new DepositBankVerified(depositId), t2)).isFalse();

		TxRow stillPending = txRows.get(depositId + ":" + walletId);
		assertThat(stillPending.status()).isEqualTo("PENDING");
		assertThat(stillPending.bankReference()).isEqualTo(legacy150);
		assertThat(dailyRows).isEmpty();

		assertThat(projector.project(UUID.randomUUID(), new DepositConfirmed(depositId), t3)).isTrue();
		assertThat(txRows.get(depositId + ":" + walletId).status()).isEqualTo("CONFIRMED");
		assertThat(dailyRows.get("2026-11-21:VND").deposit()).isEqualTo(500_000L);
	}
}
