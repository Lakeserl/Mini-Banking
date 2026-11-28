package com.lakeserl.mini_banking.deposit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.lakeserl.mini_banking.deposit.domain.events.DepositBankVerified;
import com.lakeserl.mini_banking.deposit.domain.events.DepositConfirmed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositFailed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositRequested;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.domain.TransactionLimitExceededException;
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

	@ParameterizedTest
	@ValueSource(strings = {
			"REF-1\u200B",
			"\uFEFFREF-1",
			"REF\u202E1",
			"REF\u00851",
			" REF-1",
			"REF-1 ",
			"REF 1",
			"REF-1\n",
			"REF-1\r",
			"REF\t1",
			"RЕF-1",
			"REF/1"
	})
	void requestRejectsNonAsciiFormatControlWhitespaceOrDisallowedBankReference(String invalidRef) {
		assertThatThrownBy(() -> Deposit.request(UUID.randomUUID(), UUID.randomUUID(), Money.vnd(10_000), invalidRef))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void requestEnforcesMaxBankReferenceLength100AndAllowsMaxBoundary() {
		String maxValid = "A".repeat(Deposit.MAX_BANK_REFERENCE_LENGTH);
		Deposit deposit = Deposit.request(UUID.randomUUID(), UUID.randomUUID(), Money.vnd(10_000), maxValid);
		assertThat(deposit.bankReference()).isEqualTo(maxValid);

		String tooLong = "A".repeat(Deposit.MAX_BANK_REFERENCE_LENGTH + 1);
		assertThatThrownBy(() -> Deposit.request(UUID.randomUUID(), UUID.randomUUID(), Money.vnd(10_000), tooLong))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void markBankVerifiedIsIdempotentBlocksFailAndReplaysCleanly() {
		UUID depositId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Deposit deposit = Deposit.request(depositId, walletId, Money.vnd(250_000), "REF.VER_1-ok");

		assertThat(deposit.isBankVerified()).isFalse();
		deposit.markBankVerified();
		assertThat(deposit.isBankVerified()).isTrue();
		assertThat(deposit.status()).isEqualTo(DepositStatus.PENDING);
		assertThat(deposit.uncommittedEvents()).hasSize(2);
		assertThat(deposit.uncommittedEvents().get(1).event()).isEqualTo(new DepositBankVerified(depositId));

		deposit.markBankVerified();
		assertThat(deposit.uncommittedEvents()).hasSize(2);

		assertThatThrownBy(() -> deposit.fail("late reject")).isInstanceOf(IllegalStateException.class);

		deposit.confirm();
		assertThat(deposit.status()).isEqualTo(DepositStatus.CONFIRMED);
		deposit.markBankVerified();
		assertThat(deposit.uncommittedEvents()).hasSize(3);

		Deposit replayed = Deposit.fromHistory(deposit.uncommittedEvents().stream().map(e -> e.event()).toList());
		assertThat(replayed.isBankVerified()).isTrue();
		assertThat(replayed.status()).isEqualTo(DepositStatus.CONFIRMED);
	}

	@Test
	void markBankVerifiedRejectsAlreadyFailedDeposit() {
		Deposit deposit = Deposit.request(UUID.randomUUID(), UUID.randomUUID(), Money.vnd(100_000), "REF-FAIL-V");
		deposit.fail("Bank declined");

		assertThatThrownBy(deposit::markBankVerified).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void replayAcceptsHistoricalLongBankReferenceAndLegacyConfirmedWithoutBankVerified() {
		UUID depositId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		String legacy150 = "LEGACY-" + "X".repeat(143);

		Deposit replayed = Deposit.fromHistory(List.of(
				new DepositRequested(depositId, walletId, Money.vnd(300_000), legacy150),
				new DepositConfirmed(depositId)));

		assertThat(replayed.bankReference()).isEqualTo(legacy150);
		assertThat(replayed.isBankVerified()).isFalse();
		assertThat(replayed.status()).isEqualTo(DepositStatus.CONFIRMED);
	}

	@Test
	void requestAllowsExact50MillionCapAndRejectsAboveWithLimitExceeded() {
		Deposit atCap = Deposit.request(UUID.randomUUID(), UUID.randomUUID(),
				Money.vnd(Deposit.MAX_SINGLE_DEPOSIT_VND), "REF-AT-CAP");
		assertThat(atCap.amount()).isEqualTo(Money.vnd(50_000_000L));

		assertThatThrownBy(() -> Deposit.request(UUID.randomUUID(), UUID.randomUUID(),
				Money.vnd(Deposit.MAX_SINGLE_DEPOSIT_VND + 1), "REF-OVER-CAP"))
				.isInstanceOf(TransactionLimitExceededException.class);
	}

	@Test
	void fromHistoryReplaysLegacyDepositAbove50MillionCap() {
		UUID depositId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Money legacyHuge = Money.vnd(Deposit.MAX_SINGLE_DEPOSIT_VND + 25_000_000L);

		Deposit replayed = Deposit.fromHistory(List.of(
				new DepositRequested(depositId, walletId, legacyHuge, "REF-LEGACY-BIG"),
				new DepositConfirmed(depositId)));

		assertThat(replayed.amount()).isEqualTo(legacyHuge);
		assertThat(replayed.status()).isEqualTo(DepositStatus.CONFIRMED);
	}
}
