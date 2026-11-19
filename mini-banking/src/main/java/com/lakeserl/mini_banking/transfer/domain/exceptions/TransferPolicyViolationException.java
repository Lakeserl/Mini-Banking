package com.lakeserl.mini_banking.transfer.domain.exceptions;

public class TransferPolicyViolationException extends RuntimeException {

	public TransferPolicyViolationException(String message) {
		super(message);
	}
}
