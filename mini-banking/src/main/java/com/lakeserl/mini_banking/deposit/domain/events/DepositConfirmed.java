package com.lakeserl.mini_banking.deposit.domain.events;

import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.DomainEvent;

public record DepositConfirmed(UUID depositId) implements DomainEvent {
}
