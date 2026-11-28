package com.lakeserl.mini_banking.withdrawal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.domain.TransactionLimitExceededException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InvalidAmountException;
import com.lakeserl.mini_banking.withdrawal.domain.events.WithdrawalConfirmed;
import com.lakeserl.mini_banking.withdrawal.domain.events.WithdrawalFailed;
import com.lakeserl.mini_banking.withdrawal.domain.events.WithdrawalRequested;

class WithdrawalTest {

	@Test
	void requestAndConfirmTransitionsToConfirmedAndReplaysIdentically() {
		UUID withdrawalId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();

		Withdrawal withdrawal = Withdrawal.request(withdrawalId, walletId, Money.vnd(300_000), "970436-111");
		assertThat(withdrawal.status()).isEqualTo(WithdrawalStatus.PENDING);

		withdrawal.confirm("BANK-PAY-1");
		assertThat(withdrawal.status()).isEqualTo(WithdrawalStatus.CONFIRMED);
		assertThat(withdrawal.bankReference()).isEqualTo("BANK-PAY-1");

		Withdrawal replayed = Withdrawal
				.fromHistory(withdrawal.uncommittedEvents().stream().map(e -> e.event()).toList());
		assertThat(replayed.id()).isEqualTo(withdrawalId);
		assertThat(replayed.walletId()).isEqualTo(walletId);
		assertThat(replayed.amount()).isEqualTo(Money.vnd(300_000));
		assertThat(replayed.bankAccount()).isEqualTo("****-111");
		assertThat(replayed.bankReference()).isEqualTo("BANK-PAY-1");
		assertThat(replayed.status()).isEqualTo(WithdrawalStatus.CONFIRMED);
	}

	@Test
	void maskAccountTrimsAndNeverLeaksRawDigitsIntoWithdrawalRequestedEvent() {
		assertThat(Withdrawal.maskAccount("97043612345678")).isEqualTo("****5678");
		assertThat(Withdrawal.maskAccount("12345678    ")).isEqualTo("****5678");
		assertThat(Withdrawal.maskAccount("1234")).isEqualTo("****");

		Withdrawal withdrawal = Withdrawal.request(UUID.randomUUID(), UUID.randomUUID(), Money.vnd(100_000),
				"97043612345678");
		WithdrawalRequested emitted = (WithdrawalRequested) withdrawal.uncommittedEvents().getFirst().event();
		assertThat(emitted.bankAccount()).isEqualTo("****5678").doesNotContain("970436");
	}

	@Test
	void failRecordsReasonAndRejectsFurtherTransitions() {
		Withdrawal withdrawal = Withdrawal.request(UUID.randomUUID(), UUID.randomUUID(), Money.vnd(100_000),
				"970436-222");
		withdrawal.fail("Bank payout timeout");

		assertThat(withdrawal.status()).isEqualTo(WithdrawalStatus.FAILED);
		assertThat(withdrawal.failureReason()).isEqualTo("Bank payout timeout");
		assertThatThrownBy(() -> withdrawal.confirm("BANK-PAY-2")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> withdrawal.fail("again")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void reversalTransactionIdIsDeterministicAndDistinctFromWithdrawalId() {
		UUID withdrawalId = UUID.randomUUID();
		UUID first = Withdrawal.reversalTransactionId(withdrawalId);
		UUID second = Withdrawal.reversalTransactionId(withdrawalId);

		assertThat(first).isEqualTo(second).isNotEqualTo(withdrawalId);
	}

	@Test
	void requestRejectsNonPositiveAmountAndBlankBankAccount() {
		assertThatThrownBy(() -> Withdrawal.request(UUID.randomUUID(), UUID.randomUUID(), Money.vnd(0), "970436"))
				.isInstanceOf(InvalidAmountException.class);
		assertThatThrownBy(() -> Withdrawal.request(UUID.randomUUID(), UUID.randomUUID(), Money.vnd(1_000), " "))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void replayRejectsInvalidSequenceOrMismatchedId() {
		UUID withdrawalId = UUID.randomUUID();
		assertThatThrownBy(() -> Withdrawal.fromHistory(List.of(new WithdrawalConfirmed(withdrawalId, "REF"))))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> Withdrawal.fromHistory(List.of(
				new WithdrawalRequested(withdrawalId, UUID.randomUUID(), Money.vnd(50_000), "970436"),
				new WithdrawalFailed(UUID.randomUUID(), "wrong id"))))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void requestAllowsExact50MillionCapAndRejectsAboveWithLimitExceeded() {
		Withdrawal atCap = Withdrawal.request(UUID.randomUUID(), UUID.randomUUID(),
				Money.vnd(Withdrawal.MAX_SINGLE_WITHDRAWAL_VND), "97043612345678");
		assertThat(atCap.amount()).isEqualTo(Money.vnd(50_000_000L));

		assertThatThrownBy(() -> Withdrawal.request(UUID.randomUUID(), UUID.randomUUID(),
				Money.vnd(Withdrawal.MAX_SINGLE_WITHDRAWAL_VND + 1), "97043612345678"))
				.isInstanceOf(TransactionLimitExceededException.class);
	}

	@Test
	void fromHistoryReplaysLegacyWithdrawalAbove50MillionCap() {
		UUID withdrawalId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Money legacyHuge = Money.vnd(Withdrawal.MAX_SINGLE_WITHDRAWAL_VND + 30_000_000L);

		Withdrawal replayed = Withdrawal.fromHistory(List.of(
				new WithdrawalRequested(withdrawalId, walletId, legacyHuge, "****5678"),
				new WithdrawalConfirmed(withdrawalId, "BANK-PAY-OLD")));

		assertThat(replayed.amount()).isEqualTo(legacyHuge);
		assertThat(replayed.status()).isEqualTo(WithdrawalStatus.CONFIRMED);
	}
}
