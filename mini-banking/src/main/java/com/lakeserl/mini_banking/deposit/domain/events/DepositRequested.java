package com.lakeserl.mini_banking.deposit.domain.events;

import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;

public record DepositRequested(UUID depositId, UUID walletId, Money amount, String bankReference)
		implements DomainEvent {
}
