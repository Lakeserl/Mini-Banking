package com.lakeserl.mini_banking.deposit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.lakeserl.mini_banking.deposit.domain.events.DepositConfirmed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositFailed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositRequested;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InvalidAmountException;

class DepositTest {

	@Test
	void requestAndConfirmTransitionsToConfirmedAndReplaysIdentically() {
		UUID depositId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();

		Deposit deposit = Deposit.request(depositId, walletId, Money.vnd(250_000), "REF-1");
		assertThat(deposit.status()).isEqualTo(DepositStatus.PENDING);

		deposit.confirm();
		assertThat(deposit.status()).isEqualTo(DepositStatus.CONFIRMED);
		assertThat(deposit.uncommittedEvents()).hasSize(2);

		Deposit replayed = Deposit.fromHistory(deposit.uncommittedEvents().stream().map(e -> e.event()).toList());
		assertThat(replayed.id()).isEqualTo(depositId);
		assertThat(replayed.walletId()).isEqualTo(walletId);
		assertThat(replayed.amount()).isEqualTo(Money.vnd(250_000));
		assertThat(replayed.bankReference()).isEqualTo("REF-1");
		assertThat(replayed.status()).isEqualTo(DepositStatus.CONFIRMED);
	}

	@Test
	void failRecordsReasonAndRejectsFurtherTransitions() {
		Deposit deposit = Deposit.request(UUID.randomUUID(), UUID.randomUUID(), Money.vnd(100_000), "REF-2");
		deposit.fail("Bank declined");

		assertThat(deposit.status()).isEqualTo(DepositStatus.FAILED);
		assertThat(deposit.failureReason()).isEqualTo("Bank declined");
		assertThatThrownBy(deposit::confirm).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> deposit.fail("again")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void confirmRejectsSecondConfirmOrFail() {
		Deposit deposit = Deposit.request(UUID.randomUUID(), UUID.randomUUID(), Money.vnd(100_000), "REF-3");
		deposit.confirm();

		assertThatThrownBy(deposit::confirm).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> deposit.fail("late")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void requestRejectsNonPositiveAmountAndBlankBankReference() {
		assertThatThrownBy(() -> Deposit.request(UUID.randomUUID(), UUID.randomUUID(), Money.vnd(0), "REF"))
				.isInstanceOf(InvalidAmountException.class);
		assertThatThrownBy(() -> Deposit.request(UUID.randomUUID(), UUID.randomUUID(), Money.vnd(1_000), "   "))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void replayRejectsEventsForDifferentDepositIdOrMissingInitialEvent() {
		UUID depositId = UUID.randomUUID();
		assertThatThrownBy(() -> Deposit.fromHistory(List.of(new DepositConfirmed(depositId))))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> Deposit.fromHistory(List.of(
				new DepositRequested(depositId, UUID.randomUUID(), Money.vnd(50_000), "REF"),
				new DepositFailed(UUID.randomUUID(), "wrong id"))))
				.isInstanceOf(IllegalStateException.class);
	}
}
