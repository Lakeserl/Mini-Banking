package com.lakeserl.mini_banking.deposit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.lakeserl.mini_banking.deposit.domain.events.DepositConfirmed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositFailed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositRequested;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InvalidAmountException;

class DepositTest {

	@Test
	void request_createsPendingDepositWithRequestedEvent() {
		UUID depositId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Money amount = Money.vnd(500_000);

		Deposit deposit = Deposit.request(depositId, walletId, amount, "BANK-REF-001");

		assertThat(deposit.id()).isEqualTo(depositId);
		assertThat(deposit.walletId()).isEqualTo(walletId);
		assertThat(deposit.amount()).isEqualTo(amount);
		assertThat(deposit.bankReference()).isEqualTo("BANK-REF-001");
		assertThat(deposit.status()).isEqualTo(DepositStatus.PENDING);
		assertThat(deposit.uncommittedEvents()).hasSize(1);
		assertThat(deposit.uncommittedEvents().get(0).event())
				.isEqualTo(new DepositRequested(depositId, walletId, amount, "BANK-REF-001"));
	}

	@Test
	void request_rejectsNonPositiveAmountOrBlankReference() {
		UUID depositId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();

		assertThatThrownBy(() -> Deposit.request(depositId, walletId, Money.vnd(0), "REF"))
				.isInstanceOf(InvalidAmountException.class);
		assertThatThrownBy(() -> Deposit.request(depositId, walletId, Money.vnd(100), "   "))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void confirm_transitionsFromPendingToConfirmed() {
		UUID depositId = UUID.randomUUID();
		Deposit deposit = Deposit.request(depositId, UUID.randomUUID(), Money.vnd(100_000), "REF-1");

		deposit.confirm();

		assertThat(deposit.status()).isEqualTo(DepositStatus.CONFIRMED);
		assertThat(deposit.uncommittedEvents()).hasSize(2);
		assertThat(deposit.uncommittedEvents().get(1).event()).isEqualTo(new DepositConfirmed(depositId, "REF-1"));
	}

	@Test
	void fail_transitionsFromPendingToFailed() {
		UUID depositId = UUID.randomUUID();
		Deposit deposit = Deposit.request(depositId, UUID.randomUUID(), Money.vnd(100_000), "REF-1");

		deposit.fail("Bank rejected card");

		assertThat(deposit.status()).isEqualTo(DepositStatus.FAILED);
		assertThat(deposit.failureReason()).isEqualTo("Bank rejected card");
		assertThat(deposit.uncommittedEvents().get(1).event())
				.isEqualTo(new DepositFailed(depositId, "Bank rejected card"));
	}

	@Test
	void confirmOrFail_whenAlreadyTerminal_throwsIllegalStateException() {
		UUID depositId = UUID.randomUUID();
		Deposit confirmed = Deposit.request(depositId, UUID.randomUUID(), Money.vnd(100_000), "REF-1");
		confirmed.confirm();

		assertThatThrownBy(confirmed::confirm).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> confirmed.fail("late error")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void fromStoredEvents_reconstructsStateAndRejectsEnvelopeMismatch() {
		UUID depositId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		List<StoredEvent> events = List.of(
				new StoredEvent(UUID.randomUUID(), depositId, "Deposit", 1L, "DepositRequested", 1, Instant.now(),
						depositId, null, new DepositRequested(depositId, walletId, Money.vnd(250_000), "REF-99")),
				new StoredEvent(UUID.randomUUID(), depositId, "Deposit", 2L, "DepositConfirmed", 1, Instant.now(),
						depositId, null, new DepositConfirmed(depositId, "REF-99")));

		Deposit loaded = Deposit.fromStoredEvents(events);
		assertThat(loaded.id()).isEqualTo(depositId);
		assertThat(loaded.status()).isEqualTo(DepositStatus.CONFIRMED);
		assertThat(loaded.committedVersion()).isEqualTo(2L);

		List<StoredEvent> wrongEnvelope = List.of(
				new StoredEvent(UUID.randomUUID(), UUID.randomUUID(), "Deposit", 1L, "DepositRequested", 1,
						Instant.now(), depositId, null,
						new DepositRequested(depositId, walletId, Money.vnd(250_000), "REF-99")));
		assertThatThrownBy(() -> Deposit.fromStoredEvents(wrongEnvelope))
				.isInstanceOf(IllegalStateException.class);
	}
}
