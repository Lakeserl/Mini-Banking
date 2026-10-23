package com.lakeserl.mini_banking.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

	@Test
	void plusAndMinusAreExact() {
		assertThat(Money.vnd(100).plus(Money.vnd(50))).isEqualTo(Money.vnd(150));
		assertThat(Money.vnd(100).minus(Money.vnd(150))).isEqualTo(Money.vnd(-50));
	}

	@Test
	void differentCurrenciesAreRejected() {
		Money vnd = Money.vnd(100);
		Money usd = new Money(100, Currency.USD);
		assertThatThrownBy(() -> vnd.plus(usd)).isInstanceOf(CurrencyMismatchException.class);
		assertThatThrownBy(() -> vnd.minus(usd)).isInstanceOf(CurrencyMismatchException.class);
		assertThatThrownBy(() -> vnd.compareTo(usd)).isInstanceOf(CurrencyMismatchException.class);
	}

	@Test
	void additionOverflowThrowsInsteadOfWrappingAround() {
		assertThatThrownBy(() -> Money.vnd(Long.MAX_VALUE).plus(Money.vnd(1))).isInstanceOf(ArithmeticException.class);
	}

	@Test
	void subtractionOverflowThrowsInsteadOfWrappingAround() {
		assertThatThrownBy(() -> Money.vnd(Long.MIN_VALUE).minus(Money.vnd(1))).isInstanceOf(ArithmeticException.class);
	}

	@Test
	void requiresCurrency() {
		assertThatThrownBy(() -> new Money(1, null)).isInstanceOf(NullPointerException.class);
	}
}
