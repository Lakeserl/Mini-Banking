package com.lakeserl.mini_banking.withdrawal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.StoredEvent;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InvalidAmountException;
import com.lakeserl.mini_banking.withdrawal.domain.events.WithdrawalConfirmed;
import com.lakeserl.mini_banking.withdrawal.domain.events.WithdrawalFailed;
import com.lakeserl.mini_banking.withdrawal.domain.events.WithdrawalRequested;

class WithdrawalTest {

	@Test
	void request_createsPendingWithdrawalWithRequestedEvent() {
		UUID withdrawalId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Money amount = Money.vnd(300_000);

		Withdrawal withdrawal = Withdrawal.request(withdrawalId, walletId, amount, "970436-123456789");

		assertThat(withdrawal.id()).isEqualTo(withdrawalId);
		assertThat(withdrawal.walletId()).isEqualTo(walletId);
		assertThat(withdrawal.amount()).isEqualTo(amount);
		assertThat(withdrawal.bankAccount()).isEqualTo("970436-123456789");
		assertThat(withdrawal.status()).isEqualTo(WithdrawalStatus.PENDING);
		assertThat(withdrawal.uncommittedEvents()).hasSize(1);
		assertThat(withdrawal.uncommittedEvents().get(0).event())
				.isEqualTo(new WithdrawalRequested(withdrawalId, walletId, amount, "970436-123456789"));
	}

	@Test
	void request_rejectsNonPositiveAmountOrBlankBankAccount() {
		UUID withdrawalId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();

		assertThatThrownBy(() -> Withdrawal.request(withdrawalId, walletId, Money.vnd(0), "ACC"))
				.isInstanceOf(InvalidAmountException.class);
		assertThatThrownBy(() -> Withdrawal.request(withdrawalId, walletId, Money.vnd(100), "  "))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void confirm_transitionsFromPendingToConfirmed() {
		UUID withdrawalId = UUID.randomUUID();
		Withdrawal withdrawal = Withdrawal.request(withdrawalId, UUID.randomUUID(), Money.vnd(100_000), "ACC-1");

		withdrawal.confirm("BANK-PAYOUT-01");

		assertThat(withdrawal.status()).isEqualTo(WithdrawalStatus.CONFIRMED);
		assertThat(withdrawal.bankReference()).isEqualTo("BANK-PAYOUT-01");
		assertThat(withdrawal.uncommittedEvents().get(1).event())
				.isEqualTo(new WithdrawalConfirmed(withdrawalId, "BANK-PAYOUT-01"));
	}

	@Test
	void fail_transitionsFromPendingToFailed() {
		UUID withdrawalId = UUID.randomUUID();
		Withdrawal withdrawal = Withdrawal.request(withdrawalId, UUID.randomUUID(), Money.vnd(100_000), "ACC-1");

		withdrawal.fail("Bank payout rejected");

		assertThat(withdrawal.status()).isEqualTo(WithdrawalStatus.FAILED);
		assertThat(withdrawal.failureReason()).isEqualTo("Bank payout rejected");
		assertThat(withdrawal.uncommittedEvents().get(1).event())
				.isEqualTo(new WithdrawalFailed(withdrawalId, "Bank payout rejected"));
	}

	@Test
	void confirmOrFail_whenAlreadyTerminal_throwsIllegalStateException() {
		UUID withdrawalId = UUID.randomUUID();
		Withdrawal failed = Withdrawal.request(withdrawalId, UUID.randomUUID(), Money.vnd(100_000), "ACC-1");
		failed.fail("network timeout");

		assertThatThrownBy(() -> failed.confirm("REF")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> failed.fail("again")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void fromStoredEvents_reconstructsStateAndRejectsEnvelopeMismatch() {
		UUID withdrawalId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		List<StoredEvent> events = List.of(
				new StoredEvent(UUID.randomUUID(), withdrawalId, "Withdrawal", 1L, "WithdrawalRequested", 1,
						Instant.now(), withdrawalId, null,
						new WithdrawalRequested(withdrawalId, walletId, Money.vnd(250_000), "ACC-9")),
				new StoredEvent(UUID.randomUUID(), withdrawalId, "Withdrawal", 2L, "WithdrawalConfirmed", 1,
						Instant.now(), withdrawalId, null, new WithdrawalConfirmed(withdrawalId, "PAY-99")));

		Withdrawal loaded = Withdrawal.fromStoredEvents(events);
		assertThat(loaded.id()).isEqualTo(withdrawalId);
		assertThat(loaded.status()).isEqualTo(WithdrawalStatus.CONFIRMED);
		assertThat(loaded.bankReference()).isEqualTo("PAY-99");
		assertThat(loaded.committedVersion()).isEqualTo(2L);

		List<StoredEvent> wrongEnvelope = List.of(
				new StoredEvent(UUID.randomUUID(), UUID.randomUUID(), "Withdrawal", 1L, "WithdrawalRequested", 1,
						Instant.now(), withdrawalId, null,
						new WithdrawalRequested(withdrawalId, walletId, Money.vnd(250_000), "ACC-9")));
		assertThatThrownBy(() -> Withdrawal.fromStoredEvents(wrongEnvelope))
				.isInstanceOf(IllegalStateException.class);
	}
}
