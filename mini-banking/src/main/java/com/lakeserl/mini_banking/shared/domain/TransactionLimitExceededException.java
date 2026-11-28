package com.lakeserl.mini_banking.shared.domain;

public class TransactionLimitExceededException extends RuntimeException {

	public TransactionLimitExceededException(String message) {
		super(message);
	}
}
