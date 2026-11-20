package com.lakeserl.mini_banking.transfer.saga;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.transfer.domain.Transfer;
import com.lakeserl.mini_banking.transfer.domain.TransferStatus;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferRepository;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferSagaStateStore;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;

@PostgresDataTest
class TransferConcurrencyTest {

	private static final int THREAD_COUNT = 100;
	private static final long INITIAL_SOURCE_VND = 10_000_000L;
	private static final long TRANSFER_AMOUNT_VND = 200_000L;

	@Autowired
	TransferSagaOrchestrator orchestrator;

	@Autowired
	TransferRepository transferRepository;

	@Autowired
	TransferSagaStateStore sagaStateStore;

	@Autowired
	WalletCommandHandler walletHandler;

	@Autowired
	WalletRepository walletRepository;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	private SagaRecoveryScheduler recoveryScheduler;

	@BeforeEach
	void setUp() {
		jdbc.update("DELETE FROM transfer_saga_state");
		recoveryScheduler = new SagaRecoveryScheduler(sagaStateStore, orchestrator,
				Duration.ofSeconds(30), 10, 100);
	}

	@Test
	void hundredConcurrentTransfers_from10mWalletFor200kEach_produces50Completed50FailedAndExactZeroAnd10mBalances()
			throws Exception {
		UUID sourceWalletId = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);
		walletHandler.credit(sourceWalletId, UUID.randomUUID(), Money.vnd(INITIAL_SOURCE_VND),
				"BANK_SETTLEMENT", AccountType.SETTLEMENT);
		UUID destWalletId = walletHandler.openWallet(UUID.randomUUID(), Currency.VND);

		CyclicBarrier startBarrier = new CyclicBarrier(THREAD_COUNT);
		CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
		ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
		List<UUID> transferIds = new ArrayList<>(THREAD_COUNT);
		for (int i = 0; i < THREAD_COUNT; i++) {
			transferIds.add(UUID.randomUUID());
		}

		try {
			List<Future<Transfer>> futures = new ArrayList<>(THREAD_COUNT);
			for (UUID transferId : transferIds) {
				futures.add(executor.submit(() -> {
					try {
						startBarrier.await(20, TimeUnit.SECONDS);
						return orchestrator.transfer(transferId, sourceWalletId, destWalletId,
								Money.vnd(TRANSFER_AMOUNT_VND));
					} finally {
						doneLatch.countDown();
					}
				}));
			}

			assertThat(doneLatch.await(60, TimeUnit.SECONDS)).isTrue();
			for (Future<Transfer> future : futures) {
				future.get(15, TimeUnit.SECONDS);
			}
		} finally {
			executor.shutdownNow();
		}

		// If any saga remained non-terminal under extreme contention, age non-terminal rows and let recoverOnce() finish them
		for (int sweep = 0; sweep < 5; sweep++) {
			OffsetDateTime oldTimestamp = OffsetDateTime.ofInstant(clock.instant().minusSeconds(60), ZoneOffset.UTC);
			jdbc.update("""
					UPDATE transfer_saga_state
					SET updated_at = ?
					WHERE status IN ('INITIATED', 'DEBITED', 'REVERSING')
					""", oldTimestamp);
			if (recoveryScheduler.recoverOnce() == 0) {
				break;
			}
		}

		int completedCount = 0;
		int failedCount = 0;
		for (UUID transferId : transferIds) {
			Transfer persisted = transferRepository.load(transferId).orElseThrow();
			TransferSagaStateStore.TransferSagaSummary summary = sagaStateStore.findById(transferId).orElseThrow();
			assertThat(persisted.status().isTerminal()).isTrue();
			assertThat(summary.status()).isEqualTo(persisted.status());
			if (persisted.status() == TransferStatus.COMPLETED) {
				completedCount++;
			} else if (persisted.status() == TransferStatus.FAILED) {
				failedCount++;
			}
		}

		assertThat(completedCount).isEqualTo(50);
		assertThat(failedCount).isEqualTo(50);

		Wallet sourceWallet = walletRepository.load(sourceWalletId).orElseThrow();
		Wallet destWallet = walletRepository.load(destWalletId).orElseThrow();
		assertThat(sourceWallet.balance()).isEqualTo(Money.vnd(0L));
		assertThat(destWallet.balance()).isEqualTo(Money.vnd(INITIAL_SOURCE_VND));
	}
}
