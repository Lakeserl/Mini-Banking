package com.lakeserl.mini_banking.transfer.domain.events;

import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.DomainEvent;

public record TransferDebitCompleted(UUID transferId) implements DomainEvent {
}
