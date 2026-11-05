package com.lakeserl.mini_banking.deposit.domain.events;

import java.util.Objects;
import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.DomainEvent;

public record DepositConfirmed(UUID depositId, String bankReference) implements DomainEvent {

	public DepositConfirmed {
		Objects.requireNonNull(depositId, "depositId must not be null");
		Objects.requireNonNull(bankReference, "bankReference must not be null");
		if (bankReference.isBlank()) {
			throw new IllegalArgumentException("bankReference must not be blank");
		}
	}
}
