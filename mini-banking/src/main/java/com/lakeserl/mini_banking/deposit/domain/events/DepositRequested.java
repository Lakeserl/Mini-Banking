package com.lakeserl.mini_banking.deposit.domain.events;

import java.util.Objects;
import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;

public record DepositRequested(UUID depositId, UUID walletId, Money amount, String bankReference)
		implements DomainEvent {

	public DepositRequested {
		Objects.requireNonNull(depositId, "depositId must not be null");
		Objects.requireNonNull(walletId, "walletId must not be null");
		Objects.requireNonNull(amount, "amount must not be null");
		Objects.requireNonNull(bankReference, "bankReference must not be null");
		if (bankReference.isBlank()) {
			throw new IllegalArgumentException("bankReference must not be blank");
		}
	}
}
