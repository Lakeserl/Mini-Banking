package com.lakeserl.mini_banking.wallet.domain.events;

import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.DomainEvent;

public record WalletLocked(UUID walletId, String reason) implements DomainEvent {
}
