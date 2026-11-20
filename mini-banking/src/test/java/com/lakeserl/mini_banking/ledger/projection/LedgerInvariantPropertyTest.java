package com.lakeserl.mini_banking.ledger.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.lakeserl.mini_banking.deposit.command.DepositCommandHandler;
import com.lakeserl.mini_banking.deposit.domain.Deposit;
import com.lakeserl.mini_banking.deposit.domain.DepositStatus;
import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.domain.PendingEvent;
import com.lakeserl.mini_banking.transfer.domain.Transfer;
import com.lakeserl.mini_banking.transfer.domain.TransferStatus;
import com.lakeserl.mini_banking.transfer.domain.TransferType;
import com.lakeserl.mini_banking.transfer.saga.TransferSagaOrchestrator;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.domain.WalletStatus;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InsufficientFundsException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletLockedException;
import com.lakeserl.mini_banking.withdrawal.command.WithdrawalCommandHandler;
import com.lakeserl.mini_banking.withdrawal.domain.Withdrawal;
import com.lakeserl.mini_banking.withdrawal.domain.WithdrawalStatus;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

/**
 * Property-based accounting invariant proof driving real {@link Wallet}, {@link Deposit}, {@link Withdrawal},
 * and {@link Transfer} aggregates together with the production {@link LedgerProjector} backed by an in-memory
 * double-entry ledger store enforcing the same {@code processed_events} and {@code ledger_entries} constraints
 * as PostgreSQL.
 */
class LedgerInvariantPropertyTest {

	private static final int WALLET_COUNT = 4;
	private static final String BANK_SETTLEMENT = DepositCommandHandler.BANK_SETTLEMENT_ACCOUNT;
	private static final String TRANSFER_CLEARING = TransferSagaOrchestrator.TRANSFER_CLEARING_ACCOUNT;
	private static final Instant BASE_TIME = Instant.parse("2026-11-20T00:00:00Z");

	sealed interface Op {
		record DepositConfirmed(int walletIdx, long amount) implements Op {
		}

		record DepositFailed(int walletIdx, long amount) implements Op {
		}

		record WithdrawalConfirmed(int walletIdx, long amount) implements Op {
		}

		record WithdrawalFailed(int walletIdx, long amount) implements Op {
		}

		record TransferStandard(int sourceIdx, int destOffset, long amount) implements Op {
		}

		record TransferCompensated(int sourceIdx, int destOffset, long amount, boolean lockSourceToo) implements Op {
		}

		record RefundTransfer(int pickIndex) implements Op {
		}

		record RedeliverDuplicate(int pickIndex) implements Op {
		}

		record LockWallet(int walletIdx) implements Op {
		}
	}

	record EmittedWalletEvent(UUID eventId, UUID walletId, DomainEvent event, Instant occurredAt) {
	}

	@Provide
	Arbitrary<List<Op>> operations() {
		Arbitrary<Integer> walletIdx = Arbitraries.integers().between(0, WALLET_COUNT - 1);
		Arbitrary<Integer> destOffset = Arbitraries.integers().between(1, WALLET_COUNT - 1);
		Arbitrary<Integer> pickIdx = Arbitraries.integers().between(0, 10_000);
		Arbitrary<Long> amount = Arbitraries.frequencyOf(
				Tuple.of(5, Arbitraries.longs().between(1_000L, 5_000_000L)),
				Tuple.of(3, Arbitraries.longs().between(1L, 100_000L)),
				Tuple.of(2, Arbitraries.longs().between(5_000_001L, TransferSagaOrchestrator.MAX_SINGLE_TRANSFER_VND)));

		Arbitrary<Op> depositConfirmed = Combinators.combine(walletIdx, amount).as(Op.DepositConfirmed::new);
		Arbitrary<Op> depositFailed = Combinators.combine(walletIdx, amount).as(Op.DepositFailed::new);
		Arbitrary<Op> withdrawalConfirmed = Combinators.combine(walletIdx, amount).as(Op.WithdrawalConfirmed::new);
		Arbitrary<Op> withdrawalFailed = Combinators.combine(walletIdx, amount).as(Op.WithdrawalFailed::new);
		Arbitrary<Op> transferStandard = Combinators.combine(walletIdx, destOffset, amount)
				.as(Op.TransferStandard::new);
		Arbitrary<Op> transferCompensated = Combinators
				.combine(walletIdx, destOffset, amount, Arbitraries.of(true, false))
				.as(Op.TransferCompensated::new);
		Arbitrary<Op> refundTransfer = pickIdx.map(Op.RefundTransfer::new);
		Arbitrary<Op> redeliverDuplicate = pickIdx.map(Op.RedeliverDuplicate::new);
		Arbitrary<Op> lockWallet = walletIdx.map(Op.LockWallet::new);

		Arbitrary<Op> op = Arbitraries.frequencyOf(
				Tuple.of(9, depositConfirmed),
				Tuple.of(2, depositFailed),
				Tuple.of(5, withdrawalConfirmed),
				Tuple.of(4, withdrawalFailed),
				Tuple.of(9, transferStandard),
				Tuple.of(2, transferCompensated),
				Tuple.of(4, refundTransfer),
				Tuple.of(4, redeliverDuplicate),
				Tuple.of(1, lockWallet));

		return op.list().ofMinSize(1).ofMaxSize(55);
	}

	@Property(tries = 1_500)
	void accountingInvariantsHoldAtEveryProjectedEventAndAtCompletion(@ForAll("operations") List<Op> ops) {
		SimulationResult sim = executeScenario(ops);

		// Redeliver a deterministic sample of emitted events again at completion to guarantee idempotency
		// is verified even on short chains.
		int entriesBeforeRedelivery = sim.ledger.entryCount();
		int step = Math.max(1, sim.emittedEvents.size() / 5);
		for (int i = 0; i < sim.emittedEvents.size(); i += step) {
			EmittedWalletEvent dup = sim.emittedEvents.get(i);
			boolean applied = sim.projector.project(dup.eventId(), dup.event(), dup.occurredAt());
			assertThat(applied).as("duplicate redelivery must return false").isFalse();
			assertThat(sim.ledger.entryCount()).isEqualTo(entriesBeforeRedelivery);
			assertPerEventInvariants(sim.ledger, sim.wallets, true);
		}

		assertTerminalInvariants(sim.ledger, sim.wallets, sim.walletHistories);
	}

	@Property(tries = 500)
	void crossWalletInterleavedAndDuplicateDeliveryPreservesAllInvariants(
			@ForAll("operations") List<Op> ops,
			@ForAll long shuffleSeed) {
		SimulationResult sim = executeScenario(ops);

		// Group emitted events by walletId preserving per-wallet order (matching Kafka key = aggregateId),
		// then arbitrarily interleave across wallets and inject random duplicate redeliveries.
		Map<UUID, List<EmittedWalletEvent>> perWalletQueues = new HashMap<>();
		for (Wallet w : sim.wallets) {
			perWalletQueues.put(w.id(), new ArrayList<>());
		}
		for (EmittedWalletEvent emitted : sim.emittedEvents) {
			perWalletQueues.get(emitted.walletId()).add(emitted);
		}

		List<UUID> activeWallets = new ArrayList<>();
		Map<UUID, Integer> cursors = new HashMap<>();
		for (Wallet w : sim.wallets) {
			if (!perWalletQueues.get(w.id()).isEmpty()) {
				activeWallets.add(w.id());
				cursors.put(w.id(), 0);
			}
		}

		Random random = new Random(shuffleSeed);
		InMemoryLedgerStore freshLedger = new InMemoryLedgerStore();
		LedgerProjector freshProjector = freshLedger.createProjector();
		List<EmittedWalletEvent> deliveredSoFar = new ArrayList<>();

		while (!activeWallets.isEmpty()) {
			int pick = random.nextInt(activeWallets.size());
			UUID walletId = activeWallets.get(pick);
			int cursor = cursors.get(walletId);
			List<EmittedWalletEvent> queue = perWalletQueues.get(walletId);
			EmittedWalletEvent next = queue.get(cursor);

			boolean applied = freshProjector.project(next.eventId(), next.event(), next.occurredAt());
			assertThat(applied).isTrue();
			deliveredSoFar.add(next);
			assertPerEventInvariants(freshLedger, sim.wallets, true);

			// Inject occasional duplicate redelivery of any previously delivered event.
			if (!deliveredSoFar.isEmpty() && random.nextInt(4) == 0) {
				EmittedWalletEvent dup = deliveredSoFar.get(random.nextInt(deliveredSoFar.size()));
				int before = freshLedger.entryCount();
				assertThat(freshProjector.project(dup.eventId(), dup.event(), dup.occurredAt())).isFalse();
				assertThat(freshLedger.entryCount()).isEqualTo(before);
				assertPerEventInvariants(freshLedger, sim.wallets, true);
			}

			cursor++;
			if (cursor >= queue.size()) {
				activeWallets.remove(pick);
			} else {
				cursors.put(walletId, cursor);
			}
		}

		assertTerminalInvariants(freshLedger, sim.wallets, sim.walletHistories);
	}

	private SimulationResult executeScenario(List<Op> ops) {
		InMemoryLedgerStore ledger = new InMemoryLedgerStore();
		LedgerProjector projector = ledger.createProjector();

		List<Wallet> wallets = new ArrayList<>(WALLET_COUNT);
		Map<UUID, List<DomainEvent>> walletHistories = new HashMap<>();
		List<EmittedWalletEvent> emittedEvents = new ArrayList<>();
		List<Transfer> refundableTransfers = new ArrayList<>();
		long[] clockSeconds = { 0L };

		for (int i = 0; i < WALLET_COUNT; i++) {
			UUID ownerId = UUID.randomUUID();
			UUID walletId = Wallet.calculateWalletId(ownerId, Currency.VND);
			Wallet wallet = Wallet.open(walletId, ownerId, Currency.VND);
			wallets.add(wallet);
			walletHistories.put(walletId, new ArrayList<>());
			flushWalletEvents(wallet, walletHistories, emittedEvents, projector, ledger, wallets, clockSeconds);
		}

		for (Op op : ops) {
			switch (op) {
				case Op.DepositConfirmed c -> {
					Wallet target = wallets.get(c.walletIdx());
					UUID depositId = UUID.randomUUID();
					Money amount = Money.vnd(c.amount());
					Deposit deposit = Deposit.request(depositId, target.id(), amount, "BANK-" + depositId);
					deposit.confirm();
					assertThat(deposit.status()).isEqualTo(DepositStatus.CONFIRMED);
					try {
						target.credit(depositId, amount, BANK_SETTLEMENT, AccountType.SETTLEMENT);
						flushWalletEvents(target, walletHistories, emittedEvents, projector, ledger, wallets,
								clockSeconds);
					} catch (WalletLockedException expected) {
						assertThat(target.status()).isEqualTo(WalletStatus.LOCKED);
						assertThat(target.uncommittedEvents()).isEmpty();
					}
				}
				case Op.DepositFailed f -> {
					Wallet target = wallets.get(f.walletIdx());
					UUID depositId = UUID.randomUUID();
					Deposit deposit = Deposit.request(depositId, target.id(), Money.vnd(f.amount()),
							"BANK-FAIL-" + depositId);
					deposit.fail("bank rejected");
					assertThat(deposit.status()).isEqualTo(DepositStatus.FAILED);
					assertThat(target.uncommittedEvents()).isEmpty();
					assertPerEventInvariants(ledger, wallets, true);
				}
				case Op.WithdrawalConfirmed w -> {
					Wallet target = wallets.get(w.walletIdx());
					UUID withdrawalId = UUID.randomUUID();
					Money amount = Money.vnd(w.amount());
					Withdrawal withdrawal = Withdrawal.request(withdrawalId, target.id(), amount, "970436-0001");
					try {
						target.debit(withdrawalId, amount, WithdrawalCommandHandler.BANK_SETTLEMENT_ACCOUNT,
								AccountType.SETTLEMENT);
						flushWalletEvents(target, walletHistories, emittedEvents, projector, ledger, wallets,
								clockSeconds);
						withdrawal.confirm("BANK-PAY-" + withdrawalId);
						assertThat(withdrawal.status()).isEqualTo(WithdrawalStatus.CONFIRMED);
					} catch (InsufficientFundsException | WalletLockedException expected) {
						assertThat(target.uncommittedEvents()).isEmpty();
					}
				}
				case Op.WithdrawalFailed w -> {
					Wallet target = wallets.get(w.walletIdx());
					UUID withdrawalId = UUID.randomUUID();
					Money amount = Money.vnd(w.amount());
					Withdrawal withdrawal = Withdrawal.request(withdrawalId, target.id(), amount, "970436-0002");
					try {
						target.debit(withdrawalId, amount, WithdrawalCommandHandler.BANK_SETTLEMENT_ACCOUNT,
								AccountType.SETTLEMENT);
						// Project mid-operation debit before bank rejection and reversal credit.
						flushWalletEvents(target, walletHistories, emittedEvents, projector, ledger, wallets,
								clockSeconds);
						withdrawal.fail("bank payout rejected");
						assertThat(withdrawal.status()).isEqualTo(WithdrawalStatus.FAILED);
						target.credit(Withdrawal.reversalTransactionId(withdrawalId), amount,
								WithdrawalCommandHandler.BANK_SETTLEMENT_ACCOUNT, AccountType.SETTLEMENT);
						flushWalletEvents(target, walletHistories, emittedEvents, projector, ledger, wallets,
								clockSeconds);
					} catch (InsufficientFundsException | WalletLockedException expected) {
						assertThat(target.uncommittedEvents()).isEmpty();
					}
				}
				case Op.TransferStandard t -> {
					Wallet source = wallets.get(t.sourceIdx());
					Wallet dest = wallets.get((t.sourceIdx() + t.destOffset()) % WALLET_COUNT);
					if (source.status() == WalletStatus.LOCKED || dest.status() == WalletStatus.LOCKED) {
						assertPerEventInvariants(ledger, wallets, true);
						break;
					}
					UUID transferId = UUID.randomUUID();
					Money amount = Money.vnd(t.amount());
					Transfer transfer = Transfer.initiate(transferId, source.id(), dest.id(), amount,
							TransferType.STANDARD, null);
					try {
						source.debit(transferId, amount, TRANSFER_CLEARING, AccountType.CLEARING);
						transfer.markDebitCompleted();
						assertThat(transfer.status()).isEqualTo(TransferStatus.DEBITED);

						// Mid-saga inspection right after WalletDebited, before WalletCredited is projected.
						flushWalletEvents(source, walletHistories, emittedEvents, projector, ledger, wallets,
								clockSeconds);
						assertThat(ledger.accountNetCreditMinusDebit(TRANSFER_CLEARING))
								.as("TRANSFER_CLEARING must hold in-flight transfer amount mid-saga")
								.isEqualTo(t.amount());

						dest.credit(transferId, amount, TRANSFER_CLEARING, AccountType.CLEARING);
						transfer.markCreditAndComplete();
						assertThat(transfer.status()).isEqualTo(TransferStatus.COMPLETED);
						flushWalletEvents(dest, walletHistories, emittedEvents, projector, ledger, wallets,
								clockSeconds);
						assertThat(ledger.accountNetCreditMinusDebit(TRANSFER_CLEARING))
								.as("TRANSFER_CLEARING must return to zero after transfer completion")
								.isZero();
						refundableTransfers.add(transfer);
					} catch (InsufficientFundsException | WalletLockedException ex) {
						transfer.fail(ex.getMessage());
						assertThat(transfer.status()).isEqualTo(TransferStatus.FAILED);
						assertThat(source.uncommittedEvents()).isEmpty();
						assertPerEventInvariants(ledger, wallets, true);
					}
				}
				case Op.TransferCompensated t -> {
					Wallet source = wallets.get(t.sourceIdx());
					Wallet dest = wallets.get((t.sourceIdx() + t.destOffset()) % WALLET_COUNT);
					if (source.status() == WalletStatus.LOCKED || dest.status() == WalletStatus.LOCKED) {
						assertPerEventInvariants(ledger, wallets, true);
						break;
					}
					UUID transferId = UUID.randomUUID();
					Money amount = Money.vnd(t.amount());
					Transfer transfer = Transfer.initiate(transferId, source.id(), dest.id(), amount,
							TransferType.STANDARD, null);
					try {
						source.debit(transferId, amount, TRANSFER_CLEARING, AccountType.CLEARING);
						transfer.markDebitCompleted();

						// Mid-saga inspection right after WalletDebited, before compensation.
						flushWalletEvents(source, walletHistories, emittedEvents, projector, ledger, wallets,
								clockSeconds);
						assertThat(ledger.accountNetCreditMinusDebit(TRANSFER_CLEARING))
								.as("TRANSFER_CLEARING must hold debited amount mid-saga before compensation")
								.isEqualTo(t.amount());

						// Lock destination mid-saga (and optionally lock source too before compensation).
						dest.lock("destination frozen mid-saga");
						flushWalletEvents(dest, walletHistories, emittedEvents, projector, ledger, wallets,
								clockSeconds);
						if (t.lockSourceToo()) {
							source.lock("source locked before compensation");
							flushWalletEvents(source, walletHistories, emittedEvents, projector, ledger, wallets,
									clockSeconds);
						}

						try {
							dest.credit(transferId, amount, TRANSFER_CLEARING, AccountType.CLEARING);
							throw new AssertionError("Expected WalletLockedException on locked destination");
						} catch (WalletLockedException ex) {
							transfer.startCompensation(ex.getMessage());
							assertThat(transfer.status()).isEqualTo(TransferStatus.REVERSING);
							source.creditCompensation(transferId, amount, TRANSFER_CLEARING, AccountType.CLEARING);
							transfer.markCompensated();
							assertThat(transfer.status()).isEqualTo(TransferStatus.REVERSED);
							flushWalletEvents(source, walletHistories, emittedEvents, projector, ledger, wallets,
									clockSeconds);
							assertThat(ledger.accountNetCreditMinusDebit(TRANSFER_CLEARING))
									.as("TRANSFER_CLEARING must return to zero after compensation")
									.isZero();
						}
					} catch (InsufficientFundsException ex) {
						transfer.fail(ex.getMessage());
						assertThat(transfer.status()).isEqualTo(TransferStatus.FAILED);
						assertThat(source.uncommittedEvents()).isEmpty();
					}
				}
				case Op.RefundTransfer r -> {
					if (refundableTransfers.isEmpty()) {
						break;
					}
					int idx = Math.floorMod(r.pickIndex(), refundableTransfers.size());
					Transfer original = refundableTransfers.get(idx);
					Wallet refundSource = findWallet(wallets, original.destinationWalletId());
					Wallet refundDest = findWallet(wallets, original.sourceWalletId());
					if (refundSource.status() == WalletStatus.LOCKED || refundDest.status() == WalletStatus.LOCKED) {
						assertPerEventInvariants(ledger, wallets, true);
						break;
					}
					UUID refundId = UUID.randomUUID();
					Money amount = original.amount();
					Transfer refund = Transfer.initiate(refundId, refundSource.id(), refundDest.id(), amount,
							TransferType.REFUND, original.id());
					try {
						refundSource.debit(refundId, amount, TRANSFER_CLEARING, AccountType.CLEARING);
						refund.markDebitCompleted();

						// Mid-saga inspection for refund.
						flushWalletEvents(refundSource, walletHistories, emittedEvents, projector, ledger, wallets,
								clockSeconds);
						assertThat(ledger.accountNetCreditMinusDebit(TRANSFER_CLEARING))
								.as("TRANSFER_CLEARING must hold in-flight refund amount mid-saga")
								.isEqualTo(amount.amount());

						refundDest.credit(refundId, amount, TRANSFER_CLEARING, AccountType.CLEARING);
						refund.markCreditAndComplete();
						assertThat(refund.status()).isEqualTo(TransferStatus.COMPLETED);
						flushWalletEvents(refundDest, walletHistories, emittedEvents, projector, ledger, wallets,
								clockSeconds);
						assertThat(ledger.accountNetCreditMinusDebit(TRANSFER_CLEARING)).isZero();
						refundableTransfers.remove(idx);
					} catch (InsufficientFundsException ex) {
						// Failed refund keeps original eligible for a future refund attempt.
						refund.fail(ex.getMessage());
						assertThat(refund.status()).isEqualTo(TransferStatus.FAILED);
						assertThat(refundSource.uncommittedEvents()).isEmpty();
						assertPerEventInvariants(ledger, wallets, true);
					}
				}
				case Op.RedeliverDuplicate d -> {
					if (emittedEvents.isEmpty()) {
						break;
					}
					EmittedWalletEvent dup = emittedEvents.get(Math.floorMod(d.pickIndex(), emittedEvents.size()));
					int entriesBefore = ledger.entryCount();
					boolean applied = projector.project(dup.eventId(), dup.event(), dup.occurredAt());
					assertThat(applied).as("duplicate event redelivery must be ignored").isFalse();
					assertThat(ledger.entryCount()).isEqualTo(entriesBefore);
					assertPerEventInvariants(ledger, wallets, true);
				}
				case Op.LockWallet l -> {
					Wallet target = wallets.get(l.walletIdx());
					target.lock("manual compliance lock");
					flushWalletEvents(target, walletHistories, emittedEvents, projector, ledger, wallets,
							clockSeconds);
				}
			}
		}

		return new SimulationResult(ledger, projector, wallets, walletHistories, emittedEvents);
	}

	private static Wallet findWallet(List<Wallet> wallets, UUID walletId) {
		for (Wallet w : wallets) {
			if (w.id().equals(walletId)) {
				return w;
			}
		}
		throw new IllegalStateException("Wallet not found: " + walletId);
	}

	private static void flushWalletEvents(
			Wallet wallet,
			Map<UUID, List<DomainEvent>> walletHistories,
			List<EmittedWalletEvent> emittedEvents,
			LedgerProjector projector,
			InMemoryLedgerStore ledger,
			List<Wallet> allWallets,
			long[] clockSeconds) {
		List<PendingEvent> pending = List.copyOf(wallet.uncommittedEvents());
		if (pending.isEmpty()) {
			return;
		}
		wallet.markCommitted();
		for (PendingEvent p : pending) {
			Instant occurredAt = BASE_TIME.plusSeconds(clockSeconds[0]++);
			walletHistories.get(wallet.id()).add(p.event());
			emittedEvents.add(new EmittedWalletEvent(p.eventId(), wallet.id(), p.event(), occurredAt));

			boolean applied = projector.project(p.eventId(), p.event(), occurredAt);
			assertThat(applied).as("new wallet event must be projected once").isTrue();

			assertPerEventInvariants(ledger, allWallets, true);
		}
	}

	private static void assertPerEventInvariants(
			InMemoryLedgerStore ledger,
			List<Wallet> wallets,
			boolean checkWalletNonNegative) {
		// Invariant 1: Every transaction_id has SUM(DEBIT) == SUM(CREDIT).
		assertThat(ledger.unbalancedTransactions())
				.as("every transaction must be balanced after every projected event")
				.isEmpty();

		// Invariant 2: Global SUM(DEBIT) - SUM(CREDIT) == 0 at every event (including mid-saga DEBITED).
		assertThat(ledger.totalDebitMinusCredit())
				.as("global debit minus credit must always be zero")
				.isZero();

		// Invariant 3: BANK_SETTLEMENT (DEBIT - CREDIT) == SUM(user wallets (CREDIT - DEBIT)) + TRANSFER_CLEARING (CREDIT - DEBIT).
		long userWalletsNet = 0L;
		for (Wallet wallet : wallets) {
			long walletLedgerNet = ledger.accountNetCreditMinusDebit(wallet.id().toString());
			if (checkWalletNonNegative) {
				assertThat(walletLedgerNet)
						.as("wallet %s ledger balance must never be negative", wallet.id())
						.isGreaterThanOrEqualTo(0L);
			}
			userWalletsNet += walletLedgerNet;
		}
		long clearingNet = ledger.accountNetCreditMinusDebit(TRANSFER_CLEARING);
		long settlementNet = ledger.accountNetDebitMinusCredit(BANK_SETTLEMENT);
		assertThat(settlementNet)
				.as("BANK_SETTLEMENT(D-C) must equal SUM(user wallets C-D) + TRANSFER_CLEARING(C-D)")
				.isEqualTo(userWalletsNet + clearingNet);
	}

	private static void assertTerminalInvariants(
			InMemoryLedgerStore ledger,
			List<Wallet> wallets,
			Map<UUID, List<DomainEvent>> walletHistories) {
		assertPerEventInvariants(ledger, wallets, true);

		// Invariant 4: At terminal completion, TRANSFER_CLEARING == 0, every wallet's ledger net balance
		// equals its replayed Wallet.balance(), and no wallet balance is negative.
		assertThat(ledger.accountNetCreditMinusDebit(TRANSFER_CLEARING))
				.as("TRANSFER_CLEARING must be zero once all sagas finish")
				.isZero();
		assertThat(ledger.accountNetDebitMinusCredit(TRANSFER_CLEARING))
				.as("TRANSFER_CLEARING (D-C) must be zero once all sagas finish")
				.isZero();

		long totalReplayedBalance = 0L;
		for (Wallet wallet : wallets) {
			Wallet replayed = Wallet.fromHistory(walletHistories.get(wallet.id()));
			long replayedBalance = replayed.balance().amount();
			assertThat(replayedBalance)
					.as("replayed wallet balance must equal live aggregate and be non-negative")
					.isEqualTo(wallet.balance().amount())
					.isGreaterThanOrEqualTo(0L);

			long ledgerBalance = ledger.accountNetCreditMinusDebit(wallet.id().toString());
			assertThat(ledgerBalance)
					.as("ledger balance for wallet %s must match replayed Wallet.balance()", wallet.id())
					.isEqualTo(replayedBalance);

			totalReplayedBalance += replayedBalance;
		}

		assertThat(ledger.accountNetDebitMinusCredit(BANK_SETTLEMENT))
				.as("BANK_SETTLEMENT(D-C) must equal sum of all user wallet balances")
				.isEqualTo(totalReplayedBalance);
	}

	private record SimulationResult(
			InMemoryLedgerStore ledger,
			LedgerProjector projector,
			List<Wallet> wallets,
			Map<UUID, List<DomainEvent>> walletHistories,
			List<EmittedWalletEvent> emittedEvents) {
	}

	/**
	 * In-memory double-entry store mimicking {@code processed_events} + {@code ledger_entries} SQL tables
	 * (including primary/unique/check constraints and transactional rollback) so the real {@link LedgerProjector}
	 * runs unmodified without Docker.
	 */
	static final class InMemoryLedgerStore {

		private static final Set<String> VALID_ACCOUNT_TYPES = Set.of("USER_WALLET", "SETTLEMENT", "CLEARING");
		private static final Set<String> VALID_ENTRY_TYPES = Set.of("DEBIT", "CREDIT");

		record ProcessedEventKey(String consumerGroup, UUID eventId) {
		}

		record EventEntryKey(UUID eventId, String entryType) {
		}

		record LedgerEntry(
				UUID transactionId,
				String accountId,
				String accountType,
				String entryType,
				long amount,
				String currency,
				OffsetDateTime occurredAt,
				UUID eventId) {
		}

		private final Set<ProcessedEventKey> processedEvents = new HashSet<>();
		private final Set<EventEntryKey> uniqueEventEntries = new HashSet<>();
		private final List<LedgerEntry> entries = new ArrayList<>();

		LedgerProjector createProjector() {
			return new LedgerProjector(new InMemoryLedgerJdbcTemplate(), new TransactionTemplate(new InMemoryTxManager()));
		}

		int entryCount() {
			return entries.size();
		}

		long totalDebitMinusCredit() {
			long total = 0L;
			for (LedgerEntry entry : entries) {
				total += "DEBIT".equals(entry.entryType()) ? entry.amount() : -entry.amount();
			}
			return total;
		}

		long accountNetCreditMinusDebit(String accountId) {
			long net = 0L;
			for (LedgerEntry entry : entries) {
				if (entry.accountId().equals(accountId)) {
					net += "CREDIT".equals(entry.entryType()) ? entry.amount() : -entry.amount();
				}
			}
			return net;
		}

		long accountNetDebitMinusCredit(String accountId) {
			return -accountNetCreditMinusDebit(accountId);
		}

		List<UUID> unbalancedTransactions() {
			Map<UUID, Long> perTx = new HashMap<>();
			for (LedgerEntry entry : entries) {
				long delta = "DEBIT".equals(entry.entryType()) ? entry.amount() : -entry.amount();
				perTx.merge(entry.transactionId(), delta, Long::sum);
			}
			List<UUID> unbalanced = new ArrayList<>();
			for (Map.Entry<UUID, Long> e : perTx.entrySet()) {
				if (e.getValue() != 0L) {
					unbalanced.add(e.getKey());
				}
			}
			Collections.sort(unbalanced);
			return unbalanced;
		}

		private final class InMemoryLedgerJdbcTemplate extends JdbcTemplate {

			@Override
			public int update(String sql, Object... args) {
				if (sql.contains("INSERT INTO processed_events")) {
					String consumerGroup = (String) Objects.requireNonNull(args[0], "consumerGroup");
					UUID eventId = (UUID) Objects.requireNonNull(args[1], "eventId");
					return processedEvents.add(new ProcessedEventKey(consumerGroup, eventId)) ? 1 : 0;
				}
				if (sql.contains("INSERT INTO ledger_entries")) {
					UUID transactionId = (UUID) Objects.requireNonNull(args[0], "transactionId");
					String accountId = (String) Objects.requireNonNull(args[1], "accountId");
					String accountType = (String) Objects.requireNonNull(args[2], "accountType");
					String entryType = (String) Objects.requireNonNull(args[3], "entryType");
					long amount = (Long) Objects.requireNonNull(args[4], "amount");
					String currency = (String) Objects.requireNonNull(args[5], "currency");
					OffsetDateTime occurredAt = (OffsetDateTime) Objects.requireNonNull(args[6], "occurredAt");
					UUID eventId = (UUID) Objects.requireNonNull(args[7], "eventId");

					if (!VALID_ACCOUNT_TYPES.contains(accountType)) {
						throw new IllegalArgumentException("CHECK account_type violated: " + accountType);
					}
					if (!VALID_ENTRY_TYPES.contains(entryType)) {
						throw new IllegalArgumentException("CHECK entry_type violated: " + entryType);
					}
					if (amount <= 0L) {
						throw new IllegalArgumentException("CHECK amount > 0 violated: " + amount);
					}
					if (!"VND".equals(currency)) {
						throw new IllegalArgumentException("Unsupported currency: " + currency);
					}
					if (!uniqueEventEntries.add(new EventEntryKey(eventId, entryType))) {
						throw new IllegalStateException(
								"uq_ledger_event_entry_type violated for (" + eventId + ", " + entryType + ")");
					}
					entries.add(new LedgerEntry(transactionId, accountId, accountType, entryType, amount, currency,
							occurredAt, eventId));
					return 1;
				}
				throw new UnsupportedOperationException("Unexpected SQL in InMemoryLedgerStore: " + sql);
			}
		}

		private final class InMemoryTxManager implements PlatformTransactionManager {

			private Set<ProcessedEventKey> savedProcessed;
			private Set<EventEntryKey> savedUniqueEntries;
			private int savedEntriesSize;

			@Override
			public TransactionStatus getTransaction(TransactionDefinition definition) {
				savedProcessed = new HashSet<>(processedEvents);
				savedUniqueEntries = new HashSet<>(uniqueEventEntries);
				savedEntriesSize = entries.size();
				return new SimpleTransactionStatus();
			}

			@Override
			public void commit(TransactionStatus status) {
				savedProcessed = null;
				savedUniqueEntries = null;
			}

			@Override
			public void rollback(TransactionStatus status) {
				if (savedProcessed != null) {
					processedEvents.clear();
					processedEvents.addAll(savedProcessed);
				}
				if (savedUniqueEntries != null) {
					uniqueEventEntries.clear();
					uniqueEventEntries.addAll(savedUniqueEntries);
				}
				while (entries.size() > savedEntriesSize) {
					entries.removeLast();
				}
			}
		}
	}
}
