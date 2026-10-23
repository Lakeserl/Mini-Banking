package com.lakeserl.mini_banking.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.domain.PendingEvent;
import com.lakeserl.mini_banking.wallet.domain.events.WalletCredited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletDebited;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InsufficientFundsException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InvalidAmountException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletLockedException;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Generates random <em>commands</em> (never events), runs them through the aggregate and checks the result
 * against an independent model. Because events only come from the aggregate, they are always plausible
 * (no overdrafts), unlike randomly generated events.
 */
class WalletPropertyTest {

	sealed interface Cmd {
		record Credit(long amount) implements Cmd {
		}

		record Debit(long amount) implements Cmd {
		}

		record Lock() implements Cmd {
		}
	}

	@Provide
	Arbitrary<List<Cmd>> commands() {
		Arbitrary<Long> amount = Arbitraries.frequencyOf(
				net.jqwik.api.Tuple.of(8, Arbitraries.longs().between(1, 50_000_000)),
				net.jqwik.api.Tuple.of(1, Arbitraries.longs().between(-100, 0)),
				net.jqwik.api.Tuple.of(1, Arbitraries.longs().between(1, 1_000)));
		Arbitrary<Cmd> command = Arbitraries.frequencyOf(
				net.jqwik.api.Tuple.of(10, amount.map(Cmd.Credit::new)),
				net.jqwik.api.Tuple.of(10, amount.map(Cmd.Debit::new)),
				net.jqwik.api.Tuple.of(1, Arbitraries.just((Cmd) new Cmd.Lock())));
		return command.list().ofMaxSize(100);
	}

	/** Runs the commands, checking every step against a simple model. */
	private Wallet run(List<Cmd> commands) {
		Wallet wallet = Wallet.open(UUID.randomUUID(), UUID.randomUUID(), Currency.VND);
		long model = 0;
		boolean locked = false;

		for (Cmd cmd : commands) {
			int eventsBefore = wallet.uncommittedEvents().size();
			Class<? extends Throwable> expectedError = null;

			switch (cmd) {
				case Cmd.Credit c -> {
					if (locked) {
						expectedError = WalletLockedException.class;
					} else if (c.amount() <= 0) {
						expectedError = InvalidAmountException.class;
					} else {
						model += c.amount();
					}
					expectedError = attempt(expectedError,
							() -> wallet.credit(UUID.randomUUID(), Money.vnd(c.amount()), "BANK_SETTLEMENT",
									AccountType.SETTLEMENT));
				}
				case Cmd.Debit d -> {
					if (locked) {
						expectedError = WalletLockedException.class;
					} else if (d.amount() <= 0) {
						expectedError = InvalidAmountException.class;
					} else if (d.amount() > model) {
						expectedError = InsufficientFundsException.class;
					} else {
						model -= d.amount();
					}
					expectedError = attempt(expectedError,
							() -> wallet.debit(UUID.randomUUID(), Money.vnd(d.amount()), "TRANSFER_CLEARING",
									AccountType.CLEARING));
				}
				case Cmd.Lock l -> {
					locked = true;
					wallet.lock("test");
				}
			}

			if (expectedError != null) {
				assertThat(wallet.uncommittedEvents()).as("rejected command must not emit events").hasSize(eventsBefore);
			}
			assertThat(wallet.balance().amount()).isGreaterThanOrEqualTo(0).isEqualTo(model);
		}
		return wallet;
	}

	/** Executes the action; if an error was expected it must be thrown, otherwise nothing may be thrown. */
	private Class<? extends Throwable> attempt(Class<? extends Throwable> expected, Runnable action) {
		try {
			action.run();
		} catch (RuntimeException e) {
			assertThat(expected).as("unexpected exception " + e).isNotNull();
			assertThat(e).isInstanceOf(expected);
			return expected;
		}
		assertThat(expected).as("expected an exception but none was thrown").isNull();
		return null;
	}

	private List<DomainEvent> events(Wallet wallet) {
		List<DomainEvent> events = new ArrayList<>();
		for (PendingEvent pending : wallet.uncommittedEvents()) {
			events.add(pending.event());
		}
		return events;
	}

	@Property(tries = 10_000)
	void balanceNeverNegativeAndReplayGivesSameState(@ForAll("commands") List<Cmd> commands) {
		Wallet wallet = run(commands);
		List<DomainEvent> events = events(wallet);

		Wallet replayed = Wallet.fromHistory(events);
		assertThat(replayed.balance()).isEqualTo(wallet.balance());
		assertThat(replayed.status()).isEqualTo(wallet.status());
		assertThat(replayed.committedVersion()).isEqualTo(wallet.version());

		long fromEvents = 0;
		for (DomainEvent event : events) {
			if (event instanceof WalletCredited credited) {
				fromEvents += credited.amount().amount();
			} else if (event instanceof WalletDebited debited) {
				fromEvents -= debited.amount().amount();
			}
		}
		assertThat(fromEvents).isEqualTo(wallet.balance().amount()).isGreaterThanOrEqualTo(0);
	}

	@Property(tries = 10_000)
	void snapshotPlusTailEqualsFullReplay(@ForAll("commands") List<Cmd> commands,
			@ForAll @IntRange(min = 0, max = 10_000) int cut) {
		List<DomainEvent> events = events(run(commands));
		int snapshotAt = 1 + cut % events.size(); // events always contains WalletOpened

		Wallet full = Wallet.fromHistory(events);
		WalletSnapshot snapshot = Wallet.fromHistory(events.subList(0, snapshotAt)).toSnapshot();
		Wallet fromSnapshot = Wallet.fromSnapshot(snapshot, snapshotAt);
		fromSnapshot.loadFromHistory(events.subList(snapshotAt, events.size()));

		assertThat(fromSnapshot.balance()).isEqualTo(full.balance());
		assertThat(fromSnapshot.status()).isEqualTo(full.status());
		assertThat(fromSnapshot.committedVersion()).isEqualTo(full.committedVersion());
		assertThat(fromSnapshot.toSnapshot()).isEqualTo(full.toSnapshot());
	}
}
