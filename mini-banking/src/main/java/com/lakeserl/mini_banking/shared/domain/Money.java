package com.lakeserl.mini_banking.shared.domain;

import java.util.Objects;

public record Money(long amount, Currency currency) implements Comparable<Money> {

	public Money {
		Objects.requireNonNull(currency, "currency");
	}

	public static Money vnd(long amount) {
		return new Money(amount, Currency.VND);
	}

	public static Money zero(Currency currency) {
		return new Money(0, currency);
	}

	public Money plus(Money other) {
		requireSameCurrency(other);
		return new Money(Math.addExact(amount, other.amount), currency);
	}

	public Money minus(Money other) {
		requireSameCurrency(other);
		return new Money(Math.subtractExact(amount, other.amount), currency);
	}

	public boolean isPositive() {
		return amount > 0;
	}

	public boolean isZero() {
		return amount == 0;
	}

	public boolean isNegative() {
		return amount < 0;
	}

	public boolean isGreaterThan(Money other) {
		return compareTo(other) > 0;
	}

	public boolean isGreaterThanOrEqualTo(Money other) {
		return compareTo(other) >= 0;
	}

	public boolean isLessThan(Money other) {
		return compareTo(other) < 0;
	}

	public boolean isLessThanOrEqualTo(Money other) {
		return compareTo(other) <= 0;
	}

	@Override
	public int compareTo(Money other) {
		requireSameCurrency(other);
		return Long.compare(amount, other.amount);
	}

	private void requireSameCurrency(Money other) {
		Objects.requireNonNull(other, "other");
		if (currency != other.currency) {
			throw new CurrencyMismatchException(currency, other.currency);
		}
	}
}
