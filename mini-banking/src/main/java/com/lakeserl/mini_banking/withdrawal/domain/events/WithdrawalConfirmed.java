package com.lakeserl.mini_banking.withdrawal.domain.events;

import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.DomainEvent;

public record WithdrawalConfirmed(UUID withdrawalId, String bankReference) implements DomainEvent {
}
