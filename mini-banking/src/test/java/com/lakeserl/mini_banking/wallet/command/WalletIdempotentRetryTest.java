package com.lakeserl.mini_banking.wallet.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler.ApplyResult;
import com.lakeserl.mini_banking.wallet.domain.events.WalletDebited;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InsufficientFundsException;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;

/** Simulates saga recovery retrying a step that was already applied before the pod died. */
@PostgresDataTest
class WalletIdempotentRetryTest {

	@Autowired
	WalletCommandHandler handler;

	@Autowired
	WalletRepository repository;

	@Autowired
	EventStore eventStore;

	private UUID fundedWallet(long amount) {
		UUID id = handler.openWallet(UUID.randomUUID(), Currency.VND);
		handler.credit(id, UUID.randomUUID(), Money.vnd(amount), "BANK_SETTLEMENT", AccountType.SETTLEMENT);
		return id;
	}

	private long debitEvents(UUID walletId) {
		return eventStore.load(walletId, 0).stream().map(StoredEvent::event).filter(WalletDebited.class::isInstance).count();
	}

	@Test
	void debitingTwiceWithTheSameTransactionIdWritesOneEvent() {
		UUID walletId = fundedWallet(1_000);
		UUID tx = UUID.randomUUID();

		ApplyResult first = handler.debit(walletId, tx, Money.vnd(400), "TRANSFER_CLEARING", AccountType.CLEARING);
		ApplyResult retry = handler.debit(walletId, tx, Money.vnd(400), "TRANSFER_CLEARING", AccountType.CLEARING);

		assertThat(first).isEqualTo(ApplyResult.APPLIED);
		assertThat(retry).isEqualTo(ApplyResult.ALREADY_APPLIED);
		assertThat(debitEvents(walletId)).isEqualTo(1);
		assertThat(repository.load(walletId).orElseThrow().balance()).isEqualTo(Money.vnd(600));
	}

	@Test
	void retryOfDebitThatEmptiedTheWalletIsNotReportedAsInsufficientFunds() {
		UUID walletId = fundedWallet(500);
		UUID tx = UUID.randomUUID();
		handler.debit(walletId, tx, Money.vnd(500), "TRANSFER_CLEARING", AccountType.CLEARING);

		assertThat(handler.debit(walletId, tx, Money.vnd(500), "TRANSFER_CLEARING", AccountType.CLEARING))
				.isEqualTo(ApplyResult.ALREADY_APPLIED);
		// a genuinely new transaction is still checked against the balance
		assertThatThrownBy(() -> handler.debit(walletId, UUID.randomUUID(), Money.vnd(1), "X", AccountType.CLEARING))
				.isInstanceOf(InsufficientFundsException.class);
	}

	@Test
	void creditingTwiceWithTheSameTransactionIdAddsMoneyOnce() {
		UUID walletId = fundedWallet(100);
		UUID tx = UUID.randomUUID();
		handler.credit(walletId, tx, Money.vnd(50), "TRANSFER_CLEARING", AccountType.CLEARING);
		assertThat(handler.credit(walletId, tx, Money.vnd(50), "TRANSFER_CLEARING", AccountType.CLEARING))
				.isEqualTo(ApplyResult.ALREADY_APPLIED);
		assertThat(repository.load(walletId).orElseThrow().balance()).isEqualTo(Money.vnd(150));
	}

	@Test
	void concurrentRetriesOfTheSameDebitApplyExactlyOnce() throws Exception {
		UUID walletId = fundedWallet(1_000);
		UUID tx = UUID.randomUUID();
		int retries = 10;
		ExecutorService pool = Executors.newFixedThreadPool(retries);
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<ApplyResult>> results = new ArrayList<>();
			for (int i = 0; i < retries; i++) {
				results.add(pool.submit(() -> {
					go.await();
					return handler.debit(walletId, tx, Money.vnd(300), "TRANSFER_CLEARING", AccountType.CLEARING);
				}));
			}
			go.countDown();
			int applied = 0;
			for (Future<ApplyResult> result : results) {
				if (result.get(30, TimeUnit.SECONDS) == ApplyResult.APPLIED) {
					applied++;
				}
			}
			assertThat(applied).isEqualTo(1);
			assertThat(debitEvents(walletId)).isEqualTo(1);
			assertThat(repository.load(walletId).orElseThrow().balance()).isEqualTo(Money.vnd(700));
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void retryWithDifferentAmountThrowsConflictingTransactionException() {
		UUID walletId = fundedWallet(1_000);
		UUID tx = UUID.randomUUID();

		handler.debit(walletId, tx, Money.vnd(400), "TRANSFER_CLEARING", AccountType.CLEARING);

		assertThatThrownBy(() -> handler.debit(walletId, tx, Money.vnd(500), "TRANSFER_CLEARING", AccountType.CLEARING))
				.isInstanceOf(com.lakeserl.mini_banking.wallet.domain.exceptions.ConflictingTransactionException.class);
	}

	@Test
	void retryWithDifferentCounterpartThrowsConflictingTransactionException() {
		UUID walletId = fundedWallet(1_000);
		UUID tx = UUID.randomUUID();

		handler.debit(walletId, tx, Money.vnd(400), "TRANSFER_CLEARING", AccountType.CLEARING);

		assertThatThrownBy(() -> handler.debit(walletId, tx, Money.vnd(400), "DIFFERENT_ACCOUNT", AccountType.CLEARING))
				.isInstanceOf(com.lakeserl.mini_banking.wallet.domain.exceptions.ConflictingTransactionException.class);
	}

	@Test
	void cannotOpenSecondWalletForSameOwnerAndCurrency() {
		UUID ownerId = UUID.randomUUID();
		UUID firstWalletId = handler.openWallet(ownerId, Currency.VND);
		assertThat(firstWalletId).isNotNull();

		assertThatThrownBy(() -> handler.openWallet(ownerId, Currency.VND))
				.isInstanceOf(com.lakeserl.mini_banking.wallet.domain.exceptions.WalletAlreadyExistsException.class);
	}
}
