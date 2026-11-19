package com.lakeserl.mini_banking.wallet.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.shared.eventsourcing.OptimisticConcurrencyException;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler.ApplyResult;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.domain.WalletStatus;
import com.lakeserl.mini_banking.wallet.domain.events.WalletCredited;
import com.lakeserl.mini_banking.wallet.domain.exceptions.ConflictingTransactionException;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;

class WalletCommandHandlerRetryTest {

	private WalletRepository repository;
	private EventStore eventStore;
	private UUID walletId;
	private UUID ownerId;

	@BeforeEach
	void setUp() {
		repository = mock(WalletRepository.class);
		eventStore = mock(EventStore.class);
		walletId = UUID.randomUUID();
		ownerId = UUID.randomUUID();
		when(eventStore.findEvent(any())).thenReturn(Optional.empty());
	}

	private Wallet activeWallet() {
		Wallet wallet = Wallet.open(walletId, ownerId, Currency.VND);
		wallet.markCommitted();
		return wallet;
	}

	private Wallet lockedWallet() {
		Wallet wallet = Wallet.open(walletId, ownerId, Currency.VND);
		wallet.credit(UUID.randomUUID(), Money.vnd(100), "BANK_SETTLEMENT", AccountType.SETTLEMENT);
		wallet.lock("compliance hold");
		wallet.markCommitted();
		return wallet;
	}

	@Test
	void applySucceedsOnMaxOptimisticRetriesPlusOneAttempt() {
		int maxRetries = 2;
		WalletCommandHandler handler = new WalletCommandHandler(repository, eventStore, maxRetries);
		when(repository.load(walletId)).thenAnswer(inv -> Optional.of(activeWallet()));
		AtomicInteger attempts = new AtomicInteger();
		doAnswer(inv -> {
			if (attempts.incrementAndGet() <= maxRetries) {
				throw new OptimisticConcurrencyException("conflict");
			}
			return null;
		}).when(repository).save(any(Wallet.class));

		ApplyResult result = handler.credit(walletId, UUID.randomUUID(), Money.vnd(50), "BANK_SETTLEMENT",
				AccountType.SETTLEMENT);

		assertThat(result).isEqualTo(ApplyResult.APPLIED);
		verify(repository, times(maxRetries + 1)).save(any(Wallet.class));
	}

	@Test
	void applyThrowsOptimisticConcurrencyExceptionAfterMaxOptimisticRetries() {
		int maxRetries = 2;
		WalletCommandHandler handler = new WalletCommandHandler(repository, eventStore, maxRetries);
		when(repository.load(walletId)).thenAnswer(inv -> Optional.of(activeWallet()));
		doThrow(new OptimisticConcurrencyException("conflict")).when(repository).save(any(Wallet.class));

		assertThatThrownBy(() -> handler.credit(walletId, UUID.randomUUID(), Money.vnd(50), "BANK_SETTLEMENT",
				AccountType.SETTLEMENT))
				.isInstanceOf(OptimisticConcurrencyException.class);
		verify(repository, times(maxRetries + 1)).save(any(Wallet.class));
	}

	@Test
	void lockWalletSucceedsOnMaxOptimisticRetriesPlusOneAttempt() {
		int maxRetries = 1;
		WalletCommandHandler handler = new WalletCommandHandler(repository, eventStore, maxRetries);
		when(repository.load(walletId)).thenAnswer(inv -> Optional.of(activeWallet()));
		AtomicInteger attempts = new AtomicInteger();
		doAnswer(inv -> {
			if (attempts.incrementAndGet() <= maxRetries) {
				throw new OptimisticConcurrencyException("conflict");
			}
			return null;
		}).when(repository).save(any(Wallet.class));

		handler.lockWallet(walletId, "fraud review");

		verify(repository, times(maxRetries + 1)).save(any(Wallet.class));
	}

	@Test
	void lockWalletThrowsOptimisticConcurrencyExceptionAfterMaxOptimisticRetries() {
		int maxRetries = 1;
		WalletCommandHandler handler = new WalletCommandHandler(repository, eventStore, maxRetries);
		when(repository.load(walletId)).thenAnswer(inv -> Optional.of(activeWallet()));
		doThrow(new OptimisticConcurrencyException("conflict")).when(repository).save(any(Wallet.class));

		assertThatThrownBy(() -> handler.lockWallet(walletId, "fraud review"))
				.isInstanceOf(OptimisticConcurrencyException.class);
		verify(repository, times(maxRetries + 1)).save(any(Wallet.class));
	}

	@Test
	void zeroMaxOptimisticRetriesDoesNotRetryAfterFirstConflict() {
		WalletCommandHandler handler = new WalletCommandHandler(repository, eventStore, 0);
		when(repository.load(walletId)).thenAnswer(inv -> Optional.of(activeWallet()));
		doThrow(new OptimisticConcurrencyException("conflict")).when(repository).save(any(Wallet.class));

		assertThatThrownBy(() -> handler.credit(walletId, UUID.randomUUID(), Money.vnd(50), "BANK_SETTLEMENT",
				AccountType.SETTLEMENT))
				.isInstanceOf(OptimisticConcurrencyException.class);
		verify(repository, times(1)).save(any(Wallet.class));
	}

	@Test
	void negativeMaxOptimisticRetriesIsRejected() {
		assertThatThrownBy(() -> new WalletCommandHandler(repository, eventStore, -1))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void twoArgConstructorDefaultsToMaxRetriesConstant() {
		WalletCommandHandler handler = new WalletCommandHandler(repository, eventStore);
		when(repository.load(walletId)).thenAnswer(inv -> Optional.of(activeWallet()));
		doThrow(new OptimisticConcurrencyException("conflict")).when(repository).save(any(Wallet.class));

		assertThatThrownBy(() -> handler.credit(walletId, UUID.randomUUID(), Money.vnd(50), "BANK_SETTLEMENT",
				AccountType.SETTLEMENT))
				.isInstanceOf(OptimisticConcurrencyException.class);
		verify(repository, times(WalletCommandHandler.MAX_RETRIES + 1)).save(any(Wallet.class));
	}

	@Test
	void creditCompensationAppliesOnLockedWalletWithDeterministicEventId() {
		WalletCommandHandler handler = new WalletCommandHandler(repository, eventStore, 2);
		when(repository.load(walletId)).thenAnswer(inv -> Optional.of(lockedWallet()));
		UUID tx = UUID.randomUUID();

		ApplyResult result = handler.creditCompensation(walletId, tx, Money.vnd(50), "TRANSFER_CLEARING",
				AccountType.CLEARING);

		assertThat(result).isEqualTo(ApplyResult.APPLIED);
		ArgumentCaptor<Wallet> savedCaptor = ArgumentCaptor.forClass(Wallet.class);
		verify(repository).save(savedCaptor.capture());
		Wallet saved = savedCaptor.getValue();
		assertThat(saved.status()).isEqualTo(WalletStatus.LOCKED);
		assertThat(saved.balance()).isEqualTo(Money.vnd(150));
		assertThat(saved.uncommittedEvents()).hasSize(1);
		assertThat(saved.uncommittedEvents().get(0).eventId()).isEqualTo(Wallet.creditEventId(tx, walletId));
	}

	@Test
	void creditCompensationReturnsAlreadyAppliedForIdenticalExistingEvent() {
		WalletCommandHandler handler = new WalletCommandHandler(repository, eventStore, 2);
		UUID tx = UUID.randomUUID();
		UUID eventId = Wallet.creditEventId(tx, walletId);
		WalletCredited existingEvent = new WalletCredited(walletId, Money.vnd(50), tx, "TRANSFER_CLEARING",
				AccountType.CLEARING);
		StoredEvent stored = new StoredEvent(eventId, walletId, "Wallet", 4, "WalletCredited", 1,
				Instant.now(), tx, null, existingEvent);
		when(eventStore.findEvent(eventId)).thenReturn(Optional.of(stored));

		ApplyResult result = handler.creditCompensation(walletId, tx, Money.vnd(50), "TRANSFER_CLEARING",
				AccountType.CLEARING);

		assertThat(result).isEqualTo(ApplyResult.ALREADY_APPLIED);
		verify(repository, never()).load(any());
		verify(repository, never()).save(any());
	}

	@Test
	void creditCompensationThrowsConflictingTransactionExceptionWhenPayloadDiffers() {
		WalletCommandHandler handler = new WalletCommandHandler(repository, eventStore, 2);
		UUID tx = UUID.randomUUID();
		UUID eventId = Wallet.creditEventId(tx, walletId);
		WalletCredited existingEvent = new WalletCredited(walletId, Money.vnd(50), tx, "TRANSFER_CLEARING",
				AccountType.CLEARING);
		StoredEvent stored = new StoredEvent(eventId, walletId, "Wallet", 4, "WalletCredited", 1,
				Instant.now(), tx, null, existingEvent);
		when(eventStore.findEvent(eventId)).thenReturn(Optional.of(stored));

		assertThatThrownBy(() -> handler.creditCompensation(walletId, tx, Money.vnd(75), "TRANSFER_CLEARING",
				AccountType.CLEARING))
				.isInstanceOf(ConflictingTransactionException.class);
		verify(repository, never()).load(any());
	}

	@Test
	void springContextBindsWalletCommandMaxOptimisticRetriesProperty() {
		new ApplicationContextRunner()
				.withBean(WalletRepository.class, () -> repository)
				.withBean(EventStore.class, () -> eventStore)
				.withBean(WalletCommandHandler.class)
				.withPropertyValues("wallet.command.max-optimistic-retries=1")
				.run(context -> {
					assertThat(context).hasSingleBean(WalletCommandHandler.class);
					WalletCommandHandler handler = context.getBean(WalletCommandHandler.class);
					when(repository.load(walletId)).thenAnswer(inv -> Optional.of(activeWallet()));
					doThrow(new OptimisticConcurrencyException("conflict")).when(repository).save(any(Wallet.class));

					assertThatThrownBy(() -> handler.credit(walletId, UUID.randomUUID(), Money.vnd(50),
							"BANK_SETTLEMENT", AccountType.SETTLEMENT))
							.isInstanceOf(OptimisticConcurrencyException.class);
					verify(repository, times(2)).save(any(Wallet.class));
				});
	}
}
