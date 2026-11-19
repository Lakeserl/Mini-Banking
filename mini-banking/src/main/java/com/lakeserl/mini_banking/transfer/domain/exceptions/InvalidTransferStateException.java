package com.lakeserl.mini_banking.transfer.domain.exceptions;

public class InvalidTransferStateException extends RuntimeException {

	public InvalidTransferStateException(String message) {
		super(message);
	}
}
