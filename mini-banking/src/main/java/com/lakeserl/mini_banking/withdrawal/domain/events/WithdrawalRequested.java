package com.lakeserl.mini_banking.withdrawal.domain.events;

import java.util.Objects;
import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;

public record WithdrawalRequested(UUID withdrawalId, UUID walletId, Money amount, String bankAccount)
		implements DomainEvent {

	public WithdrawalRequested {
		Objects.requireNonNull(withdrawalId, "withdrawalId must not be null");
		Objects.requireNonNull(walletId, "walletId must not be null");
		Objects.requireNonNull(amount, "amount must not be null");
		Objects.requireNonNull(bankAccount, "bankAccount must not be null");
		if (bankAccount.isBlank()) {
			throw new IllegalArgumentException("bankAccount must not be blank");
		}
	}
}
