package com.lakeserl.mini_banking.wallet.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import com.lakeserl.mini_banking.shared.domain.Currency;

/**
 * Serialised wallet state at a given version. Bump {@link #SCHEMA_VERSION} whenever this record or the
 * meaning of its fields changes; snapshots with another schema version are discarded and the wallet is
 * rebuilt by replaying events. A snapshot is an optimisation only, events stay the source of truth.
 */
public record WalletSnapshot(UUID walletId, UUID ownerId, Currency currency, long balance, WalletStatus status) {

	public static final int SCHEMA_VERSION = 2;

	public String integrityHash() {
		String canonicalState = String.join("\n", walletId.toString(), ownerId.toString(), currency.name(),
				Long.toString(balance), status.name());
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(canonicalState.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 algorithm not available", e);
		}
	}
}
