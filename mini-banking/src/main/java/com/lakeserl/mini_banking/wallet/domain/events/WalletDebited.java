package com.lakeserl.mini_banking.wallet.domain.events;

import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;

public record WalletDebited(UUID walletId, Money amount, UUID transactionId,
		String counterpartAccountId, AccountType counterpartAccountType) implements DomainEvent {
}
