package com.lakeserl.mini_banking.shared.domain;

/** Thrown when two {@link Money} values of different currencies are combined or compared. */
public class CurrencyMismatchException extends IllegalArgumentException {

	public CurrencyMismatchException(Currency expected, Currency actual) {
		super("Currency mismatch: expected " + expected + " but got " + actual);
	}
}
