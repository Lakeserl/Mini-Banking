package com.lakeserl.mini_banking.withdrawal.domain.events;

import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.DomainEvent;

public record WithdrawalFailed(UUID withdrawalId, String reason) implements DomainEvent {
}
