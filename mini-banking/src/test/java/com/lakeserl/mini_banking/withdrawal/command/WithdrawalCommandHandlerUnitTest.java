package com.lakeserl.mini_banking.withdrawal.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.lakeserl.mini_banking.mockbank.MockBankGateway;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.PayoutResult;
import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStore;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.domain.exceptions.ConflictingTransactionException;
import com.lakeserl.mini_banking.withdrawal.domain.Withdrawal;
import com.lakeserl.mini_banking.withdrawal.domain.WithdrawalStatus;
import com.lakeserl.mini_banking.withdrawal.infrastructure.WithdrawalRepository;

class WithdrawalCommandHandlerUnitTest {

	private WithdrawalRepository withdrawalRepository;
	private WalletCommandHandler walletCommandHandler;
	private MockBankGateway mockBankGateway;
	private EventStore eventStore;
	private WithdrawalCommandHandler handler;

	@BeforeEach
	void setUp() {
		withdrawalRepository = mock(WithdrawalRepository.class);
		walletCommandHandler = mock(WalletCommandHandler.class);
		mockBankGateway = mock(MockBankGateway.class);
		eventStore = mock(EventStore.class);
		handler = new WithdrawalCommandHandler(withdrawalRepository, walletCommandHandler, mockBankGateway, eventStore);
	}

	@Test
	void withdrawPassesRawAccountToBankButStoresMaskedAccountAndUsesCreditCompensationOnFailure() {
		UUID withdrawalId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Money amount = Money.vnd(250_000);
		String rawAccount = "97043612345678";

		when(withdrawalRepository.load(withdrawalId)).thenReturn(Optional.empty());
		when(mockBankGateway.payout(withdrawalId, amount, rawAccount)).thenReturn(PayoutResult.rejected("declined"));

		WithdrawalStatus status = handler.withdraw(withdrawalId, walletId, amount, rawAccount);

		assertThat(status).isEqualTo(WithdrawalStatus.FAILED);
		verify(mockBankGateway).payout(withdrawalId, amount, rawAccount);
		verify(walletCommandHandler).creditCompensation(
				eq(walletId), eq(Withdrawal.reversalTransactionId(withdrawalId)), eq(amount),
				eq(WithdrawalCommandHandler.BANK_SETTLEMENT_ACCOUNT), eq(AccountType.SETTLEMENT));
		verify(walletCommandHandler, never()).credit(any(), any(), any(), any(), any());

		ArgumentCaptor<Withdrawal> saved = ArgumentCaptor.forClass(Withdrawal.class);
		verify(withdrawalRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
		assertThat(saved.getValue().bankAccount()).isEqualTo("****5678");
	}

	@Test
	void retryWithSameRawAccountMatchesMaskedStoredAccountAndFailedRetryUsesCreditCompensation() {
		UUID withdrawalId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Money amount = Money.vnd(100_000);
		Withdrawal existing = Withdrawal.request(withdrawalId, walletId, amount, "97043612345678");
		existing.fail("timeout");
		when(withdrawalRepository.load(withdrawalId)).thenReturn(Optional.of(existing));

		WithdrawalStatus status = handler.withdraw(withdrawalId, walletId, amount, "97043612345678");

		assertThat(status).isEqualTo(WithdrawalStatus.FAILED);
		verify(walletCommandHandler).creditCompensation(
				eq(walletId), eq(Withdrawal.reversalTransactionId(withdrawalId)), eq(amount),
				eq(WithdrawalCommandHandler.BANK_SETTLEMENT_ACCOUNT), eq(AccountType.SETTLEMENT));

		assertThatThrownBy(() -> handler.withdraw(withdrawalId, walletId, amount, "97043612349999"))
				.isInstanceOf(ConflictingTransactionException.class);
	}

	@Test
	void initialWithdrawalSaveFailureAfterDebitCompensatesWalletAndRethrows() {
		UUID withdrawalId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Money amount = Money.vnd(300_000);

		when(withdrawalRepository.load(withdrawalId)).thenReturn(Optional.empty());
		org.mockito.Mockito.doThrow(new RuntimeException("db connection lost"))
				.when(withdrawalRepository).save(any(Withdrawal.class));

		assertThatThrownBy(() -> handler.withdraw(withdrawalId, walletId, amount, "97043612345678"))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("db connection lost");

		verify(walletCommandHandler).debit(
				eq(walletId), eq(withdrawalId), eq(amount),
				eq(WithdrawalCommandHandler.BANK_SETTLEMENT_ACCOUNT), eq(AccountType.SETTLEMENT));
		verify(walletCommandHandler).creditCompensation(
				eq(walletId), eq(Withdrawal.reversalTransactionId(withdrawalId)), eq(amount),
				eq(WithdrawalCommandHandler.BANK_SETTLEMENT_ACCOUNT), eq(AccountType.SETTLEMENT));
		verify(mockBankGateway, never()).payout(any(), any(), any());
	}

	@Test
	void retryAfterSaveFailureCompensationReturnsFailedWithoutCallingBankPayout() {
		UUID withdrawalId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Money amount = Money.vnd(300_000);
		UUID reversalEventId = Wallet.creditEventId(Withdrawal.reversalTransactionId(withdrawalId), walletId);

		when(withdrawalRepository.load(withdrawalId)).thenReturn(Optional.empty());
		when(eventStore.contains(reversalEventId)).thenReturn(true);

		WithdrawalStatus status = handler.withdraw(withdrawalId, walletId, amount, "97043612345678");

		assertThat(status).isEqualTo(WithdrawalStatus.FAILED);
		verify(walletCommandHandler, never()).debit(any(), any(), any(), any(), any());
		verify(mockBankGateway, never()).payout(any(), any(), any());
	}

	@Test
	void saveFailureWhenPersistenceVerificationLoadAlsoFailsDoesNotCompensate() {
		UUID withdrawalId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		Money amount = Money.vnd(300_000);

		when(withdrawalRepository.load(withdrawalId))
				.thenReturn(Optional.empty())
				.thenThrow(new RuntimeException("db still down"));
		org.mockito.Mockito.doThrow(new RuntimeException("save timeout"))
				.when(withdrawalRepository).save(any(Withdrawal.class));

		assertThatThrownBy(() -> handler.withdraw(withdrawalId, walletId, amount, "97043612345678"))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("save timeout");

		verify(walletCommandHandler, never()).creditCompensation(any(), any(), any(), any(), any());
		verify(mockBankGateway, never()).payout(any(), any(), any());
	}
}
