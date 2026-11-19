package com.lakeserl.mini_banking.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.domain.PendingEvent;
import com.lakeserl.mini_banking.shared.eventsourcing.EventSerializer;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;
import com.lakeserl.mini_banking.transfer.domain.events.TransferCompensated;
import com.lakeserl.mini_banking.transfer.domain.events.TransferCompensationStarted;
import com.lakeserl.mini_banking.transfer.domain.events.TransferCompleted;
import com.lakeserl.mini_banking.transfer.domain.events.TransferCreditCompleted;
import com.lakeserl.mini_banking.transfer.domain.events.TransferDebitCompleted;
import com.lakeserl.mini_banking.transfer.domain.events.TransferFailed;
import com.lakeserl.mini_banking.transfer.domain.events.TransferInitiated;
import com.lakeserl.mini_banking.transfer.domain.exceptions.InvalidTransferStateException;
import com.lakeserl.mini_banking.transfer.domain.exceptions.TransferPolicyViolationException;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferEventTypes;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferRepository;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InvalidAmountException;

class TransferTest {

	@Test
	void transferStatusTerminalFlagsMatchLifecycle() {
		assertThat(TransferStatus.INITIATED.isTerminal()).isFalse();
		assertThat(TransferStatus.DEBITED.isTerminal()).isFalse();
		assertThat(TransferStatus.REVERSING.isTerminal()).isFalse();
		assertThat(TransferStatus.COMPLETED.isTerminal()).isTrue();
		assertThat(TransferStatus.FAILED.isTerminal()).isTrue();
		assertThat(TransferStatus.REVERSED.isTerminal()).isTrue();
	}

	@Test
	void standardTransferHappyPathTransitionsAndReplaysIdentically() {
		UUID transferId = UUID.randomUUID();
		UUID sourceWalletId = UUID.randomUUID();
		UUID destinationWalletId = UUID.randomUUID();

		Transfer transfer = Transfer.initiate(transferId, sourceWalletId, destinationWalletId, Money.vnd(500_000),
				TransferType.STANDARD, null);
		assertThat(transfer.status()).isEqualTo(TransferStatus.INITIATED);
		assertThat(transfer.uncommittedEvents()).hasSize(1);

		transfer.markDebitCompleted();
		assertThat(transfer.status()).isEqualTo(TransferStatus.DEBITED);
		assertThat(transfer.uncommittedEvents()).hasSize(2);

		transfer.markCreditAndComplete();
		assertThat(transfer.status()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(transfer.uncommittedEvents()).hasSize(4);
		assertThat(transfer.uncommittedEvents().stream().map(PendingEvent::event).toList()).containsExactly(
				new TransferInitiated(transferId, sourceWalletId, destinationWalletId, Money.vnd(500_000),
						TransferType.STANDARD, null),
				new TransferDebitCompleted(transferId),
				new TransferCreditCompleted(transferId),
				new TransferCompleted(transferId));
		assertThat(transfer.uncommittedEvents()).allSatisfy(pending -> {
			assertThat(pending.correlationId()).isEqualTo(transferId);
			assertThat(pending.causationId()).isNull();
		});

		Transfer replayed = Transfer.fromHistory(
				transfer.uncommittedEvents().stream().map(PendingEvent::event).toList());
		assertThat(replayed.id()).isEqualTo(transferId);
		assertThat(replayed.sourceWalletId()).isEqualTo(sourceWalletId);
		assertThat(replayed.destinationWalletId()).isEqualTo(destinationWalletId);
		assertThat(replayed.amount()).isEqualTo(Money.vnd(500_000));
		assertThat(replayed.type()).isEqualTo(TransferType.STANDARD);
		assertThat(replayed.originalTransferId()).isNull();
		assertThat(replayed.status()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(replayed.failureReason()).isNull();
		assertThat(replayed.committedVersion()).isEqualTo(4);
	}

	@Test
	void failFromInitiatedRecordsReasonAndReplaysIdentically() {
		UUID transferId = UUID.randomUUID();
		Transfer transfer = Transfer.initiate(transferId, UUID.randomUUID(), UUID.randomUUID(), Money.vnd(100_000),
				TransferType.STANDARD, null);

		transfer.fail("Insufficient funds");
		assertThat(transfer.status()).isEqualTo(TransferStatus.FAILED);
		assertThat(transfer.failureReason()).isEqualTo("Insufficient funds");
		assertThat(transfer.uncommittedEvents().getLast().event())
				.isEqualTo(new TransferFailed(transferId, "Insufficient funds"));
		assertThat(transfer.uncommittedEvents().getLast().correlationId()).isEqualTo(transferId);

		Transfer replayed = Transfer.fromHistory(
				transfer.uncommittedEvents().stream().map(PendingEvent::event).toList());
		assertThat(replayed.status()).isEqualTo(TransferStatus.FAILED);
		assertThat(replayed.failureReason()).isEqualTo("Insufficient funds");
	}

	@Test
	void compensationPathFromDebitedTransitionsToReversingThenReversed() {
		UUID transferId = UUID.randomUUID();
		Transfer transfer = Transfer.initiate(transferId, UUID.randomUUID(), UUID.randomUUID(), Money.vnd(250_000),
				TransferType.STANDARD, null);
		transfer.markDebitCompleted();

		transfer.startCompensation("Destination wallet locked");
		assertThat(transfer.status()).isEqualTo(TransferStatus.REVERSING);
		assertThat(transfer.failureReason()).isEqualTo("Destination wallet locked");
		assertThat(transfer.uncommittedEvents().getLast().event())
				.isEqualTo(new TransferCompensationStarted(transferId, "Destination wallet locked"));

		transfer.markCompensated();
		assertThat(transfer.status()).isEqualTo(TransferStatus.REVERSED);
		assertThat(transfer.uncommittedEvents().getLast().event())
				.isEqualTo(new TransferCompensated(transferId));
		assertThat(transfer.uncommittedEvents()).allSatisfy(p -> assertThat(p.correlationId()).isEqualTo(transferId));

		Transfer replayed = Transfer.fromHistory(
				transfer.uncommittedEvents().stream().map(PendingEvent::event).toList());
		assertThat(replayed.status()).isEqualTo(TransferStatus.REVERSED);
		assertThat(replayed.failureReason()).isEqualTo("Destination wallet locked");
		assertThat(replayed.committedVersion()).isEqualTo(4);
	}

	@Test
	void illegalTransitionsFromNonTerminalStatesThrowInvalidTransferStateException() {
		Transfer initiated = Transfer.initiate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				Money.vnd(50_000), TransferType.STANDARD, null);
		assertThatThrownBy(initiated::markCreditAndComplete).isInstanceOf(InvalidTransferStateException.class);
		assertThatThrownBy(() -> initiated.startCompensation("too early"))
				.isInstanceOf(InvalidTransferStateException.class);
		assertThatThrownBy(initiated::markCompensated).isInstanceOf(InvalidTransferStateException.class);

		Transfer debited = Transfer.initiate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				Money.vnd(50_000), TransferType.STANDARD, null);
		debited.markDebitCompleted();
		assertThatThrownBy(debited::markDebitCompleted).isInstanceOf(InvalidTransferStateException.class);
		assertThatThrownBy(() -> debited.fail("cannot fail after debit"))
				.isInstanceOf(InvalidTransferStateException.class);
		assertThatThrownBy(debited::markCompensated).isInstanceOf(InvalidTransferStateException.class);

		Transfer reversing = Transfer.initiate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				Money.vnd(50_000), TransferType.STANDARD, null);
		reversing.markDebitCompleted();
		reversing.startCompensation("credit failed");
		assertThatThrownBy(reversing::markDebitCompleted).isInstanceOf(InvalidTransferStateException.class);
		assertThatThrownBy(reversing::markCreditAndComplete).isInstanceOf(InvalidTransferStateException.class);
		assertThatThrownBy(() -> reversing.fail("already reversing"))
				.isInstanceOf(InvalidTransferStateException.class);
		assertThatThrownBy(() -> reversing.startCompensation("again"))
				.isInstanceOf(InvalidTransferStateException.class);
	}

	@Test
	void terminalStatesRejectAllFurtherTransitions() {
		Transfer completed = Transfer.initiate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				Money.vnd(50_000), TransferType.STANDARD, null);
		completed.markDebitCompleted();
		completed.markCreditAndComplete();
		assertAllTransitionsRejected(completed);

		Transfer failed = Transfer.initiate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				Money.vnd(50_000), TransferType.STANDARD, null);
		failed.fail("declined");
		assertAllTransitionsRejected(failed);

		Transfer reversed = Transfer.initiate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				Money.vnd(50_000), TransferType.STANDARD, null);
		reversed.markDebitCompleted();
		reversed.startCompensation("credit failed");
		reversed.markCompensated();
		assertAllTransitionsRejected(reversed);
	}

	private static void assertAllTransitionsRejected(Transfer terminal) {
		assertThatThrownBy(terminal::markDebitCompleted).isInstanceOf(InvalidTransferStateException.class);
		assertThatThrownBy(terminal::markCreditAndComplete).isInstanceOf(InvalidTransferStateException.class);
		assertThatThrownBy(() -> terminal.fail("late")).isInstanceOf(InvalidTransferStateException.class);
		assertThatThrownBy(() -> terminal.startCompensation("late"))
				.isInstanceOf(InvalidTransferStateException.class);
		assertThatThrownBy(terminal::markCompensated).isInstanceOf(InvalidTransferStateException.class);
	}

	@Test
	void initiateEnforcesMoneyCurrencySameWalletAndTypeInvariants() {
		UUID sameWallet = UUID.randomUUID();
		assertThatThrownBy(() -> Transfer.initiate(UUID.randomUUID(), sameWallet, sameWallet, Money.vnd(10_000),
				TransferType.STANDARD, null))
				.isInstanceOf(TransferPolicyViolationException.class);

		assertThatThrownBy(() -> Transfer.initiate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				Money.vnd(0), TransferType.STANDARD, null))
				.isInstanceOf(InvalidAmountException.class);
		assertThatThrownBy(() -> Transfer.initiate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				Money.vnd(-5_000), TransferType.STANDARD, null))
				.isInstanceOf(InvalidAmountException.class);

		assertThatThrownBy(() -> Transfer.initiate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				new Money(10_000, Currency.USD), TransferType.STANDARD, null))
				.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> Transfer.initiate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				Money.vnd(10_000), TransferType.REFUND, null))
				.isInstanceOf(IllegalArgumentException.class);

		UUID originalId = UUID.randomUUID();
		assertThatThrownBy(() -> Transfer.initiate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				Money.vnd(10_000), TransferType.STANDARD, originalId))
				.isInstanceOf(IllegalArgumentException.class);

		Transfer refund = Transfer.initiate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				Money.vnd(10_000), TransferType.REFUND, originalId);
		assertThat(refund.type()).isEqualTo(TransferType.REFUND);
		assertThat(refund.originalTransferId()).isEqualTo(originalId);
	}

	@Test
	void failAndStartCompensationRejectBlankReason() {
		Transfer t1 = Transfer.initiate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Money.vnd(10_000),
				TransferType.STANDARD, null);
		assertThatThrownBy(() -> t1.fail("   ")).isInstanceOf(IllegalArgumentException.class);

		Transfer t2 = Transfer.initiate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Money.vnd(10_000),
				TransferType.STANDARD, null);
		t2.markDebitCompleted();
		assertThatThrownBy(() -> t2.startCompensation("")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void eventIdsAreDeterministicPerTransferAndEventType() {
		UUID transferId = UUID.randomUUID();
		UUID source = UUID.randomUUID();
		UUID dest = UUID.randomUUID();

		Transfer first = Transfer.initiate(transferId, source, dest, Money.vnd(20_000), TransferType.STANDARD, null);
		first.markDebitCompleted();
		first.markCreditAndComplete();

		Transfer second = Transfer.initiate(transferId, source, dest, Money.vnd(20_000), TransferType.STANDARD, null);
		second.markDebitCompleted();
		second.markCreditAndComplete();

		List<UUID> firstIds = first.uncommittedEvents().stream().map(PendingEvent::eventId).toList();
		List<UUID> secondIds = second.uncommittedEvents().stream().map(PendingEvent::eventId).toList();
		assertThat(firstIds).isEqualTo(secondIds);
		assertThat(firstIds).doesNotHaveDuplicates();
	}

	@Test
	void replayRejectsMissingInitialEventMismatchedTransferIdAndInvalidEnvelope() {
		UUID transferId = UUID.randomUUID();
		UUID source = UUID.randomUUID();
		UUID dest = UUID.randomUUID();

		assertThatThrownBy(() -> Transfer.fromHistory(List.of(new TransferDebitCompleted(transferId))))
				.isInstanceOf(IllegalStateException.class);

		assertThatThrownBy(() -> Transfer.fromHistory(List.of(
				new TransferInitiated(transferId, source, dest, Money.vnd(10_000), TransferType.STANDARD, null),
				new TransferDebitCompleted(UUID.randomUUID()))))
				.isInstanceOf(IllegalStateException.class);

		assertThatThrownBy(() -> Transfer.fromHistory(List.of(
				new TransferInitiated(transferId, source, dest, Money.vnd(10_000), TransferType.STANDARD, null),
				new TransferCompleted(transferId))))
				.isInstanceOf(InvalidTransferStateException.class);

		DomainEvent initiated = new TransferInitiated(transferId, source, dest, Money.vnd(10_000),
				TransferType.STANDARD, null);
		StoredEvent wrongAggregateType = new StoredEvent(UUID.randomUUID(), transferId, "Deposit", 1,
				initiated.eventType(), 1, Instant.now(), transferId, null, initiated);
		assertThatThrownBy(() -> Transfer.fromStoredEvents(List.of(wrongAggregateType)))
				.isInstanceOf(IllegalStateException.class);

		StoredEvent wrongAggregateId = new StoredEvent(UUID.randomUUID(), UUID.randomUUID(), "Transfer", 1,
				initiated.eventType(), 1, Instant.now(), transferId, null, initiated);
		assertThatThrownBy(() -> Transfer.fromStoredEvents(List.of(wrongAggregateId)))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void repositorySavesAndReloadsTransferAndEventTypesRegistersAllSevenEvents() {
		TransferEventTypes eventTypes = new TransferEventTypes();
		assertThat(eventTypes.eventTypes()).containsExactlyInAnyOrder(
				TransferInitiated.class,
				TransferDebitCompleted.class,
				TransferCreditCompleted.class,
				TransferCompleted.class,
				TransferFailed.class,
				TransferCompensationStarted.class,
				TransferCompensated.class);

		EventSerializer serializer = new EventSerializer(List.of(eventTypes));
		UUID sampleId = UUID.randomUUID();
		UUID origId = UUID.randomUUID();
		List<DomainEvent> allEvents = List.of(
				new TransferInitiated(sampleId, UUID.randomUUID(), UUID.randomUUID(), Money.vnd(10_000),
						TransferType.STANDARD, null),
				new TransferInitiated(sampleId, UUID.randomUUID(), UUID.randomUUID(), Money.vnd(10_000),
						TransferType.REFUND, origId),
				new TransferDebitCompleted(sampleId),
				new TransferCreditCompleted(sampleId),
				new TransferCompleted(sampleId),
				new TransferFailed(sampleId, "Insufficient funds"),
				new TransferCompensationStarted(sampleId, "Destination locked"),
				new TransferCompensated(sampleId));
		for (DomainEvent event : allEvents) {
			String json = serializer.toJson(event);
			assertThat(serializer.deserialize(event.eventType(), json)).isEqualTo(event);
		}

		InMemoryEventStore store = new InMemoryEventStore();
		TransferRepository repository = new TransferRepository(store);

		UUID transferId = UUID.randomUUID();
		UUID source = UUID.randomUUID();
		UUID dest = UUID.randomUUID();
		assertThat(repository.load(transferId)).isEmpty();

		Transfer transfer = Transfer.initiate(transferId, source, dest, Money.vnd(75_000), TransferType.STANDARD, null);
		transfer.markDebitCompleted();
		transfer.markCreditAndComplete();
		repository.save(transfer);

		assertThat(transfer.uncommittedEvents()).isEmpty();
		assertThat(transfer.committedVersion()).isEqualTo(4);

		Transfer loaded = repository.load(transferId).orElseThrow();
		assertThat(loaded.id()).isEqualTo(transferId);
		assertThat(loaded.sourceWalletId()).isEqualTo(source);
		assertThat(loaded.destinationWalletId()).isEqualTo(dest);
		assertThat(loaded.amount()).isEqualTo(Money.vnd(75_000));
		assertThat(loaded.status()).isEqualTo(TransferStatus.COMPLETED);
		assertThat(loaded.committedVersion()).isEqualTo(4);
	}

	private static final class InMemoryEventStore implements EventStore {
		private final List<StoredEvent> events = new ArrayList<>();

		@Override
		public void append(UUID aggregateId, String aggregateType, long expectedVersion, List<PendingEvent> pending) {
			long version = expectedVersion;
			for (PendingEvent p : pending) {
				version++;
				events.add(new StoredEvent(p.eventId(), aggregateId, aggregateType, version, p.event().eventType(), 1,
						Instant.now(), p.correlationId(), p.causationId(), p.event()));
			}
		}

		@Override
		public List<StoredEvent> load(UUID aggregateId, long afterVersion) {
			return events.stream()
					.filter(e -> e.aggregateId().equals(aggregateId) && e.version() > afterVersion)
					.toList();
		}

		@Override
		public boolean contains(UUID eventId) {
			return events.stream().anyMatch(e -> e.eventId().equals(eventId));
		}

		@Override
		public Optional<StoredEvent> findEvent(UUID eventId) {
			return events.stream().filter(e -> e.eventId().equals(eventId)).findFirst();
		}

		@Override
		public long latestVersion(UUID aggregateId) {
			return events.stream().filter(e -> e.aggregateId().equals(aggregateId))
					.mapToLong(StoredEvent::version).max().orElse(0);
		}
	}
}
