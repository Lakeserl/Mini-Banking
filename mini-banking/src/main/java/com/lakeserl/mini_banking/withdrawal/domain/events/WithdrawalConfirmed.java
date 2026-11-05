package com.lakeserl.mini_banking.withdrawal.domain.events;

import java.util.Objects;
import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.DomainEvent;

public record WithdrawalConfirmed(UUID withdrawalId, String bankReference) implements DomainEvent {

	public WithdrawalConfirmed {
		Objects.requireNonNull(withdrawalId, "withdrawalId must not be null");
		Objects.requireNonNull(bankReference, "bankReference must not be null");
		if (bankReference.isBlank()) {
			throw new IllegalArgumentException("bankReference must not be blank");
		}
	}
}
