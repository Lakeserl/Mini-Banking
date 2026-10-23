package com.lakeserl.mini_banking.wallet.domain.exceptions;

public class InsufficientFundsException extends RuntimeException {

	public InsufficientFundsException(String message) {
		super(message);
	}
}
