package com.lakeserl.mini_banking.withdrawal.domain.events;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;

public record WithdrawalRequested(UUID withdrawalId, UUID walletId, Money amount, String bankAccount,
		String bankAccountHash) implements DomainEvent {

	@JsonCreator
	public WithdrawalRequested {
	}

	public WithdrawalRequested(UUID withdrawalId, UUID walletId, Money amount, String bankAccount) {
		this(withdrawalId, walletId, amount, bankAccount, null);
	}
}
