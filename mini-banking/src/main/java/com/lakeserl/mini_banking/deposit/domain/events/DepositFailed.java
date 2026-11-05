package com.lakeserl.mini_banking.deposit.domain.events;

import java.util.Objects;
import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.DomainEvent;

public record DepositFailed(UUID depositId, String reason) implements DomainEvent {

	public DepositFailed {
		Objects.requireNonNull(depositId, "depositId must not be null");
		Objects.requireNonNull(reason, "reason must not be null");
		if (reason.isBlank()) {
			throw new IllegalArgumentException("reason must not be blank");
		}
	}
}
