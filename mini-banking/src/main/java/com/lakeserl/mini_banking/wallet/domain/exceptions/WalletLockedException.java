package com.lakeserl.mini_banking.wallet.domain.exceptions;

public class WalletLockedException extends RuntimeException {

	public WalletLockedException(String message) {
		super(message);
	}
}
