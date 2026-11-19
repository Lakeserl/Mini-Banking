package com.lakeserl.mini_banking.withdrawal.domain.events;

import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;

public record WithdrawalRequested(UUID withdrawalId, UUID walletId, Money amount, String bankAccount)
		implements DomainEvent {
}
