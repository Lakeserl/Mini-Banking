package com.lakeserl.mini_banking.wallet.domain;

import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.Currency;

/**
 * Serialised wallet state at a given version. Bump {@link #SCHEMA_VERSION} whenever this record or the
 * meaning of its fields changes; snapshots with another schema version are discarded and the wallet is
 * rebuilt by replaying events. A snapshot is an optimisation only, events stay the source of truth.
 */
public record WalletSnapshot(UUID walletId, UUID ownerId, Currency currency, long balance, WalletStatus status) {

	public static final int SCHEMA_VERSION = 1;
}
