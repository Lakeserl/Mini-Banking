package com.lakeserl.mini_banking.deposit.domain.exceptions;

import java.util.Objects;

public class DuplicateBankReferenceException extends RuntimeException {

	private static final String DEFAULT_MESSAGE = "Bank reference has already been used";

	private final String bankReference;

	public DuplicateBankReferenceException(String bankReference) {
		super(DEFAULT_MESSAGE);
		this.bankReference = Objects.requireNonNull(bankReference, "bankReference must not be null");
	}

	public String bankReference() {
		return bankReference;
	}
}
