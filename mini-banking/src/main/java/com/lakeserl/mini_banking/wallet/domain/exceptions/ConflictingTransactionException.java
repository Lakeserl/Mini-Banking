package com.lakeserl.mini_banking.wallet.domain.exceptions;

import java.util.UUID;

public class ConflictingTransactionException extends RuntimeException {

	public ConflictingTransactionException(UUID transactionId, String message) {
		super("Transaction conflict for id " + transactionId + ": " + message);
	}
}
