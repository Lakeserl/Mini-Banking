package com.lakeserl.mini_banking.wallet.domain.exceptions;

public class InvalidAmountException extends RuntimeException {

	public InvalidAmountException(String message) {
		super(message);
	}
}
