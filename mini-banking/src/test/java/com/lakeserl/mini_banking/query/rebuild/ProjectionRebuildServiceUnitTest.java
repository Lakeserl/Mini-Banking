package com.lakeserl.mini_banking.query.rebuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
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
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.lakeserl.mini_banking.deposit.domain.events.DepositConfirmed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositRequested;
import com.lakeserl.mini_banking.ledger.projection.LedgerProjector;
import com.lakeserl.mini_banking.query.projection.BalanceProjector;
import com.lakeserl.mini_banking.query.projection.TransactionHistoryProjector;
import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.shared.eventsourcing.GlobalStoredEvent;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;
import com.lakeserl.mini_banking.wallet.domain.events.WalletCredited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletOpened;

class ProjectionRebuildServiceUnitTest {

	private EventStore eventStore;
	private JdbcTemplate jdbc;
	private TransactionTemplate tx;
	private LedgerProjector ledgerProjector;
	private BalanceProjector balanceProjector;
	private TransactionHistoryProjector txHistoryProjector;
	private KafkaListenerEndpointRegistry registry;
	private MessageListenerContainer container;
	private ProjectionRebuildService service;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		eventStore = mock(EventStore.class);
		jdbc = mock(JdbcTemplate.class);
		when(jdbc.queryForObject(contains("pg_try_advisory_xact_lock"), eq(Boolean.class), anyLong()))
				.thenReturn(Boolean.TRUE);
		tx = mock(TransactionTemplate.class);
		when(tx.execute(any(TransactionCallback.class))).thenAnswer(inv -> {
			TransactionCallback<Object> cb = inv.getArgument(0);
			return cb.doInTransaction(mock(TransactionStatus.class));
		});
		ledgerProjector = mock(LedgerProjector.class);
		when(ledgerProjector.consumerGroup()).thenReturn(LedgerProjector.DEFAULT_CONSUMER_GROUP);
		balanceProjector = mock(BalanceProjector.class);
		when(balanceProjector.consumerGroup()).thenReturn(BalanceProjector.DEFAULT_CONSUMER_GROUP);
		txHistoryProjector = mock(TransactionHistoryProjector.class);
		when(txHistoryProjector.consumerGroup()).thenReturn(TransactionHistoryProjector.DEFAULT_CONSUMER_GROUP);

		registry = mock(KafkaListenerEndpointRegistry.class);
		container = mock(MessageListenerContainer.class);
		when(container.isRunning()).thenReturn(true);
		when(registry.getListenerContainers()).thenReturn(List.of(container));

		ObjectProvider<KafkaListenerEndpointRegistry> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(registry);

		Clock fixedClock = Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"), ZoneOffset.UTC);
		service = new ProjectionRebuildService(eventStore, jdbc, tx, ledgerProjector, balanceProjector,
				txHistoryProjector, provider, fixedClock, 2);
	}

	@Test
	void rebuildStopsListenersClearsReplaysAllBatchesAtomicallyAndStarts() {
		UUID walletId = UUID.randomUUID();
		UUID ownerId = UUID.randomUUID();
		UUID depositId = UUID.randomUUID();
		Instant t1 = Instant.parse("2026-11-21T08:00:00Z");

		GlobalStoredEvent e1 = new GlobalStoredEvent(1L, new StoredEvent(UUID.randomUUID(), walletId, "Wallet", 1L,
				"WalletOpened", 1, t1, walletId, null, new WalletOpened(walletId, ownerId, Currency.VND)));
		GlobalStoredEvent e2 = new GlobalStoredEvent(2L, new StoredEvent(UUID.randomUUID(), depositId, "Deposit", 1L,
				"DepositRequested", 1, t1.plusSeconds(1), depositId, null,
				new DepositRequested(depositId, walletId, Money.vnd(500_000), "REF-1")));
		GlobalStoredEvent e3 = new GlobalStoredEvent(3L, new StoredEvent(UUID.randomUUID(), walletId, "Wallet", 2L,
				"WalletCredited", 1, t1.plusSeconds(2), depositId, null,
				new WalletCredited(walletId, Money.vnd(500_000), depositId, "BANK_SETTLEMENT",
						AccountType.SETTLEMENT)));
		GlobalStoredEvent e4 = new GlobalStoredEvent(4L, new StoredEvent(UUID.randomUUID(), depositId, "Deposit", 2L,
				"DepositConfirmed", 1, t1.plusSeconds(3), depositId, null, new DepositConfirmed(depositId)));

		when(eventStore.loadBatch(0L, 2)).thenReturn(List.of(e1, e2));
		when(eventStore.loadBatch(2L, 2)).thenReturn(List.of(e3, e4));
		when(eventStore.loadBatch(4L, 2)).thenReturn(List.of());

		ProjectionRebuildService.RebuildResult result = service.rebuildAll();

		assertThat(result.eventsReplayed()).isEqualTo(4L);
		assertThat(result.completedAt()).isEqualTo(Instant.parse("2026-11-21T10:00:00Z"));
		verify(tx, times(1)).execute(any(TransactionCallback.class));

		InOrder order = inOrder(container, jdbc, ledgerProjector, balanceProjector, txHistoryProjector);
		order.verify(container).stop();
		order.verify(jdbc).queryForObject(contains("pg_try_advisory_xact_lock"), eq(Boolean.class), anyLong());
		order.verify(jdbc).update("DELETE FROM ledger_entries");
		order.verify(jdbc).update("DELETE FROM wallet_balances");
		order.verify(jdbc).update("DELETE FROM transaction_history");
		order.verify(jdbc).update("DELETE FROM daily_reports");
		order.verify(jdbc).update(contains("DELETE FROM processed_events"),
				eq(LedgerProjector.DEFAULT_CONSUMER_GROUP),
				eq(BalanceProjector.DEFAULT_CONSUMER_GROUP),
				eq(TransactionHistoryProjector.DEFAULT_CONSUMER_GROUP));
		order.verify(ledgerProjector).project(e1.storedEvent().eventId(), e1.storedEvent().event(), t1);
		order.verify(balanceProjector).project(e1.storedEvent().eventId(), e1.storedEvent().event(), 1L, t1);
		order.verify(txHistoryProjector).project(e2.storedEvent().eventId(), e2.storedEvent().event(),
				t1.plusSeconds(1));
		order.verify(ledgerProjector).project(e3.storedEvent().eventId(), e3.storedEvent().event(),
				t1.plusSeconds(2));
		order.verify(balanceProjector).project(e3.storedEvent().eventId(), e3.storedEvent().event(), 2L,
				t1.plusSeconds(2));
		order.verify(txHistoryProjector).project(e4.storedEvent().eventId(), e4.storedEvent().event(),
				t1.plusSeconds(3));
		order.verify(container).start();
		verify(jdbc, never()).execute(anyString());
	}

	@Test
	void rebuildStartsListenersAndReleasesLockEvenWhenReplayThrows() {
		when(eventStore.loadBatch(0L, 2))
				.thenThrow(new IllegalStateException("db hiccup"))
				.thenReturn(List.of());

		assertThatThrownBy(() -> service.rebuildAll()).isInstanceOf(IllegalStateException.class);
		verify(container).stop();
		verify(container).start();

		assertThat(service.rebuildAll().eventsReplayed()).isZero();
	}

	@Test
	void stopFailureStillStartsEarlierContainersAndReleasesLock() {
		MessageListenerContainer c1 = mock(MessageListenerContainer.class);
		MessageListenerContainer c2 = mock(MessageListenerContainer.class);
		when(c1.isRunning()).thenReturn(true);
		when(c2.isRunning()).thenReturn(true);
		doThrow(new IllegalStateException("stop failed")).when(c2).stop();
		when(registry.getListenerContainers()).thenReturn(List.of(c1, c2));

		assertThatThrownBy(() -> service.rebuildAll())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("stop failed");
		verify(c1).stop();
		verify(c1).start();

		when(registry.getListenerContainers()).thenReturn(List.of(container));
		when(eventStore.loadBatch(0L, 2)).thenReturn(List.of());
		assertThat(service.rebuildAll().eventsReplayed()).isZero();
	}

	@Test
	void advisoryLockContentionThrowsRebuildInProgressExceptionAndRestartsContainers() {
		when(jdbc.queryForObject(contains("pg_try_advisory_xact_lock"), eq(Boolean.class), anyLong()))
				.thenReturn(Boolean.FALSE);

		assertThatThrownBy(() -> service.rebuildAll())
				.isInstanceOf(ProjectionRebuildService.RebuildInProgressException.class);

		verify(container).stop();
		verify(container).start();
		verify(jdbc, never()).update(anyString());
		verify(eventStore, never()).loadBatch(anyLong(), anyInt());
	}

	@Test
	void rebuildRejectsCallInsideActiveOuterTransactionWithoutWedgingRunningGuard() {
		TransactionSynchronizationManager.setActualTransactionActive(true);
		try {
			assertThatThrownBy(() -> service.rebuildAll())
					.isInstanceOf(IllegalStateException.class);
			verify(container, never()).stop();
			verify(container, never()).start();
			verify(tx, never()).execute(any(TransactionCallback.class));
		} finally {
			TransactionSynchronizationManager.setActualTransactionActive(false);
		}

		when(eventStore.loadBatch(0L, 2)).thenReturn(List.of());
		assertThat(service.rebuildAll().eventsReplayed()).isZero();
	}

	@Test
	void concurrentRebuildThrowsRebuildInProgressException() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		when(eventStore.loadBatch(anyLong(), anyInt())).thenAnswer(inv -> {
			entered.countDown();
			release.await(5, TimeUnit.SECONDS);
			return new ArrayList<>();
		});

		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<ProjectionRebuildService.RebuildResult> first = pool.submit(() -> service.rebuildAll());
			assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

			assertThatThrownBy(() -> service.rebuildAll())
					.isInstanceOf(ProjectionRebuildService.RebuildInProgressException.class);

			release.countDown();
			assertThat(first.get(5, TimeUnit.SECONDS).eventsReplayed()).isZero();
		} finally {
			pool.shutdownNow();
		}
	}
}
