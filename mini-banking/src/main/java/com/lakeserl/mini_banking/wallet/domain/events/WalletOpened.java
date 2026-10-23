package com.lakeserl.mini_banking.wallet.domain.events;

import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.DomainEvent;

public record WalletOpened(UUID walletId, UUID ownerId, Currency currency) implements DomainEvent {
}
