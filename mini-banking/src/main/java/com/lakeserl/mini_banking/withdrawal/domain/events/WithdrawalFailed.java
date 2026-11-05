package com.lakeserl.mini_banking.withdrawal.domain.events;

import java.util.Objects;
import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.DomainEvent;

public record WithdrawalFailed(UUID withdrawalId, String reason) implements DomainEvent {

	public WithdrawalFailed {
		Objects.requireNonNull(withdrawalId, "withdrawalId must not be null");
		Objects.requireNonNull(reason, "reason must not be null");
		if (reason.isBlank()) {
			throw new IllegalArgumentException("reason must not be blank");
		}
	}
}
