package com.lakeserl.mini_banking.wallet.domain.exceptions;

import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.Currency;

public class WalletAlreadyExistsException extends RuntimeException {

	public WalletAlreadyExistsException(UUID ownerId, Currency currency) {
		super("Wallet already exists for owner " + ownerId + " and currency " + currency);
	}

	public WalletAlreadyExistsException(String message) {
		super(message);
	}
}
