package com.lakeserl.mini_banking.transfer.domain;

public enum TransferStatus {
	INITIATED,
	DEBITED,
	COMPLETED,
	FAILED,
	REVERSING,
	REVERSED;

	public boolean isTerminal() {
		return this == COMPLETED || this == FAILED || this == REVERSED;
	}
}
