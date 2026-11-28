package com.lakeserl.mini_banking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.lakeserl.mini_banking.deposit.command.DepositCommandHandler;
import com.lakeserl.mini_banking.shared.api.ApiExceptionHandler;
import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.EventSerializer;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.shared.eventsourcing.PostgresSnapshotStore;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;
import com.lakeserl.mini_banking.transfer.domain.Transfer;
import com.lakeserl.mini_banking.transfer.domain.TransferStatus;
import com.lakeserl.mini_banking.transfer.domain.TransferType;
import com.lakeserl.mini_banking.transfer.domain.exceptions.TransferPolicyViolationException;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferRepository;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferSagaStateStore;
import com.lakeserl.mini_banking.transfer.saga.TransferSagaOrchestrator;
import com.lakeserl.mini_banking.wallet.api.WalletController;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.domain.WalletSnapshot;
import com.lakeserl.mini_banking.wallet.domain.WalletStatus;
import com.lakeserl.mini_banking.wallet.domain.events.WalletCredited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletOpened;
import com.lakeserl.mini_banking.wallet.domain.exceptions.ConflictingTransactionException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InsufficientFundsException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletLockedException;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;
import com.lakeserl.mini_banking.withdrawal.command.WithdrawalCommandHandler;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class SensitiveDataRedactionTest {

	@Test
	void walletDebitInsufficientFundsNeverExposesBalance() {
		UUID walletId = UUID.randomUUID();
		Wallet wallet = Wallet.open(walletId, UUID.randomUUID(), Currency.VND);
		wallet.credit(UUID.randomUUID(), Money.vnd(123_456_789L), "BANK_SETTLEMENT", AccountType.SETTLEMENT);

		assertThatThrownBy(() -> wallet.debit(UUID.randomUUID(), Money.vnd(200_000_000L),
				"TRANSFER_CLEARING", AccountType.CLEARING))
				.isInstanceOf(InsufficientFundsException.class)
				.hasMessage("Insufficient funds")
				.message()
				.doesNotContain("123456789");
	}

	@Test
	@SuppressWarnings("unchecked")
	void transferSagaFailureReasonsUseFixedCodesWithoutLeakingExceptionMessages() {
		TransferRepository transferRepo = mock(TransferRepository.class);
		TransferSagaStateStore sagaStore = mock(TransferSagaStateStore.class);
		WalletRepository walletRepo = mock(WalletRepository.class);
		WalletCommandHandler walletCmd = mock(WalletCommandHandler.class);
		EventStore eventStore = mock(EventStore.class);
		TransactionTemplate tx = mock(TransactionTemplate.class);
		when(tx.execute(any(TransactionCallback.class))).thenAnswer(inv -> {
			TransactionCallback<Object> cb = inv.getArgument(0);
			return cb.doInTransaction(mock(TransactionStatus.class));
		});
		doAnswer(inv -> {
			inv.<java.util.function.Consumer<TransactionStatus>>getArgument(0).accept(mock(TransactionStatus.class));
			return null;
		}).when(tx).executeWithoutResult(any());
		when(eventStore.findEvent(any())).thenReturn(Optional.empty());

		TransferSagaOrchestrator orchestrator = new TransferSagaOrchestrator(
				transferRepo, sagaStore, walletRepo, walletCmd, eventStore, tx, 0, 0, 0);

		// 1. InsufficientFundsException during debit -> fixed INSUFFICIENT_FUNDS, never leaks ex.getMessage()
		UUID t1Id = UUID.randomUUID();
		UUID src1 = UUID.randomUUID();
		UUID dst1 = UUID.randomUUID();
		AtomicReference<Transfer> saved1 = new AtomicReference<>(
				Transfer.initiate(t1Id, src1, dst1, Money.vnd(100_000L), TransferType.STANDARD, null));
		saved1.get().markCommitted();
		when(transferRepo.load(t1Id)).thenAnswer(inv -> Optional.of(saved1.get()));
		doAnswer(inv -> {
			Transfer t = inv.getArgument(0);
			t.markCommitted();
			saved1.set(t);
			return null;
		}).when(transferRepo).save(any(Transfer.class));
		doThrow(new InsufficientFundsException("secret balance 987654321")).when(walletCmd)
				.debit(eq(src1), eq(t1Id), any(), anyString(), any());

		Transfer res1 = orchestrator.resume(t1Id, 0, 3);
		assertThat(res1.status()).isEqualTo(TransferStatus.FAILED);
		assertThat(res1.failureReason()).isEqualTo("INSUFFICIENT_FUNDS").doesNotContain("987654321");

		// 2. Max retries exceeded during debit -> fixed DEBIT_FAILED, never leaks ex.getMessage()
		UUID t2Id = UUID.randomUUID();
		UUID src2 = UUID.randomUUID();
		UUID dst2 = UUID.randomUUID();
		AtomicReference<Transfer> saved2 = new AtomicReference<>(
				Transfer.initiate(t2Id, src2, dst2, Money.vnd(100_000L), TransferType.STANDARD, null));
		saved2.get().markCommitted();
		when(transferRepo.load(t2Id)).thenAnswer(inv -> Optional.of(saved2.get()));
		doThrow(new RuntimeException("internal db secret 555666777")).when(walletCmd)
				.debit(eq(src2), eq(t2Id), any(), anyString(), any());

		Transfer res2 = orchestrator.resume(t2Id, 3, 3);
		assertThat(res2.status()).isEqualTo(TransferStatus.FAILED);
		assertThat(res2.failureReason()).isEqualTo("DEBIT_FAILED").doesNotContain("555666777");

		// 3. WalletLockedException during credit -> fixed WALLET_LOCKED, never leaks ex.getMessage()
		UUID t3Id = UUID.randomUUID();
		UUID src3 = UUID.randomUUID();
		UUID dst3 = UUID.randomUUID();
		Transfer t3 = Transfer.initiate(t3Id, src3, dst3, Money.vnd(100_000L), TransferType.STANDARD, null);
		t3.markDebitCompleted();
		t3.markCommitted();
		AtomicReference<Transfer> saved3 = new AtomicReference<>(t3);
		when(transferRepo.load(t3Id)).thenAnswer(inv -> Optional.of(saved3.get()));
		doThrow(new WalletLockedException("locked secret 111222333")).when(walletCmd)
				.credit(eq(dst3), eq(t3Id), any(), anyString(), any());

		Transfer res3 = orchestrator.resume(t3Id, 0, 3);
		assertThat(res3.status()).isEqualTo(TransferStatus.REVERSED);
		assertThat(res3.failureReason()).isEqualTo("WALLET_LOCKED").doesNotContain("111222333");

		// 4. Max retries exceeded during credit -> fixed CREDIT_FAILED, never leaks ex.getMessage()
		UUID t4Id = UUID.randomUUID();
		UUID src4 = UUID.randomUUID();
		UUID dst4 = UUID.randomUUID();
		Transfer t4 = Transfer.initiate(t4Id, src4, dst4, Money.vnd(100_000L), TransferType.STANDARD, null);
		t4.markDebitCompleted();
		t4.markCommitted();
		AtomicReference<Transfer> saved4 = new AtomicReference<>(t4);
		when(transferRepo.load(t4Id)).thenAnswer(inv -> Optional.of(saved4.get()));
		doThrow(new RuntimeException("credit internal 444555666")).when(walletCmd)
				.credit(eq(dst4), eq(t4Id), any(), anyString(), any());

		Transfer res4 = orchestrator.resume(t4Id, 3, 3);
		assertThat(res4.status()).isEqualTo(TransferStatus.REVERSED);
		assertThat(res4.failureReason()).isEqualTo("CREDIT_FAILED").doesNotContain("444555666");
	}

	@Test
	void walletCommandHandlerConflictingTransactionNeverDumpsEventPayloads() {
		WalletRepository repo = mock(WalletRepository.class);
		EventStore eventStore = mock(EventStore.class);
		WalletCommandHandler handler = new WalletCommandHandler(repo, eventStore, 0);

		UUID walletId = UUID.randomUUID();
		UUID txId = UUID.randomUUID();
		UUID eventId = Wallet.creditEventId(txId, walletId);
		WalletCredited storedCredit = new WalletCredited(walletId, Money.vnd(777_888_999L), txId,
				"BANK_SETTLEMENT", AccountType.SETTLEMENT);
		when(eventStore.findEvent(eventId)).thenReturn(Optional.of(new StoredEvent(
				eventId, walletId, "Wallet", 2L, "WalletCredited", 1, Instant.now(), txId, null, storedCredit)));

		assertThatThrownBy(() -> handler.credit(walletId, txId, Money.vnd(111_222_333L),
				"BANK_SETTLEMENT", AccountType.SETTLEMENT))
				.isInstanceOf(ConflictingTransactionException.class)
				.message()
				.doesNotContain("777888999")
				.doesNotContain("111222333")
				.doesNotContain("WalletCredited");
	}

	@Test
	@SuppressWarnings("unchecked")
	void transferDailyLimitViolationNeverExposesSpent24h() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		Clock clock = Clock.fixed(Instant.parse("2026-11-28T00:00:00Z"), ZoneOffset.UTC);
		TransferSagaStateStore store = new TransferSagaStateStore(jdbc, clock);

		UUID transferId = UUID.randomUUID();
		UUID sourceId = UUID.randomUUID();
		UUID destId = UUID.randomUUID();

		when(jdbc.query(anyString(), any(RowMapper.class), anyString())).thenReturn(List.of());
		when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(transferId))).thenReturn(Boolean.FALSE);
		when(jdbc.queryForObject(anyString(), eq(Long.class), eq(sourceId), any())).thenReturn(180_123_456L);

		assertThatThrownBy(() -> store.insertInitiated(
				transferId, sourceId, destId, Money.vnd(30_000_000L), TransferType.STANDARD, null, 200_000_000L))
				.isInstanceOf(TransferPolicyViolationException.class)
				.message()
				.doesNotContain("180123456")
				.doesNotContain("spent24h");
	}

	@Test
	void walletRepositoryNegativeSnapshotLogNeverExposesBalance() {
		EventStore eventStore = mock(EventStore.class);
		PostgresSnapshotStore snapshotStore = mock(PostgresSnapshotStore.class);
		EventSerializer serializer = new EventSerializer(List.of());
		WalletRepository repo = new WalletRepository(eventStore, snapshotStore, serializer);

		UUID walletId = UUID.randomUUID();
		UUID ownerId = UUID.randomUUID();
		UUID lastEventId = UUID.randomUUID();
		WalletSnapshot corrupt = new WalletSnapshot(walletId, ownerId, Currency.VND, -987_654_321L,
				WalletStatus.ACTIVE);
		PostgresSnapshotStore.Snapshot snapshot = new PostgresSnapshotStore.Snapshot(
				walletId, "Wallet", 1L, lastEventId, WalletSnapshot.SCHEMA_VERSION,
				corrupt.integrityHash(), serializer.toJson(corrupt));

		when(snapshotStore.find(walletId, WalletSnapshot.SCHEMA_VERSION)).thenReturn(Optional.of(snapshot));
		when(eventStore.latestVersion(walletId)).thenReturn(1L);
		StoredEvent openEvent = new StoredEvent(lastEventId, walletId, "Wallet", 1L, "WalletOpened", 1,
				Instant.now(), walletId, null, new WalletOpened(walletId, ownerId, Currency.VND));
		when(eventStore.findEvent(lastEventId)).thenReturn(Optional.of(openEvent));
		when(eventStore.load(walletId, 0L)).thenReturn(List.of(openEvent));

		Logger logger = (Logger) LoggerFactory.getLogger(WalletRepository.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			Optional<Wallet> loaded = repo.load(walletId);
			assertThat(loaded).isPresent();
			assertThat(appender.list).isNotEmpty();
			assertThat(appender.list)
					.extracting(ILoggingEvent::getFormattedMessage)
					.allSatisfy(msg -> assertThat(msg).doesNotContain("987654321"));
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void withdrawRequestToStringMasksBankAccountAndHandlesNullSafely() {
		WalletController.WithdrawRequest populated = new WalletController.WithdrawRequest(
				100_000L, Currency.VND, "97043612345678");
		assertThat(populated.toString())
				.contains("****5678")
				.doesNotContain("9704361234");

		WalletController.WithdrawRequest nullAccount = new WalletController.WithdrawRequest(
				100_000L, Currency.VND, null);
		assertThat(nullAccount.toString()).doesNotContain("970436");

		WalletController.WithdrawRequest blankAccount = new WalletController.WithdrawRequest(
				100_000L, Currency.VND, "   ");
		assertThat(blankAccount.toString()).doesNotContain("   ");
	}

	@Test
	void validationErrorResponseDoesNotEchoRejectedBankAccount() throws Exception {
		WalletController controller = new WalletController(
				mock(WalletCommandHandler.class),
				mock(WalletRepository.class),
				mock(DepositCommandHandler.class),
				mock(WithdrawalCommandHandler.class));
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new ApiExceptionHandler())
				.build();

		String rawAccount = "97043699887766";
		String responseBody = mockMvc.perform(post("/api/wallets/{id}/withdraw", UUID.randomUUID())
						.header("Idempotency-Key", "val-key-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":-100,\"currency\":\"VND\",\"bankAccount\":\"" + rawAccount + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(responseBody).doesNotContain(rawAccount);
	}
}
