package com.lakeserl.mini_banking.transfer.domain.events;

import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.transfer.domain.TransferType;

public record TransferInitiated(UUID transferId, UUID sourceWalletId, UUID destinationWalletId, Money amount,
		TransferType type, UUID originalTransferId) implements DomainEvent {
}
