package com.lakeserl.mini_banking.shared.domain;

/** Kind of the account on the other side of a wallet movement (needed to build balanced ledger entries). */
public enum AccountType {
	USER_WALLET,
	SETTLEMENT,
	CLEARING
}
