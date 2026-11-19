package com.lakeserl.mini_banking.transfer.domain.exceptions;

import java.util.UUID;

public class TransferNotFoundException extends RuntimeException {

	public TransferNotFoundException(UUID transferId) {
		super("Transfer not found: " + transferId);
	}
}
