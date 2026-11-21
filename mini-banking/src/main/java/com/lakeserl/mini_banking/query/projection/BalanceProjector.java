package com.lakeserl.mini_banking.query.projection;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.wallet.domain.events.WalletCredited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletDebited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletLocked;
import com.lakeserl.mini_banking.wallet.domain.events.WalletOpened;

/**
 * Idempotent read-side projector maintaining {@code wallet_balances} with {@code aggregate_version}
 * atomically alongside {@code processed_events}.
 */
@Service
public class BalanceProjector {

	public static final String DEFAULT_CONSUMER_GROUP = "balance-projector";
	public static final int MAX_CONSUMER_GROUP_LENGTH = 50;

	private static final String INSERT_PROCESSED_SQL = """
			INSERT INTO processed_events (consumer_group, event_id, processed_at)
			VALUES (?, ?, now())
			ON CONFLICT (consumer_group, event_id) DO NOTHING
			""";

	private static final String INSERT_WALLET_SQL = """
			INSERT INTO wallet_balances (wallet_id, owner_id, currency, balance, status, aggregate_version, updated_at)
			VALUES (?, ?, ?, 0, 'ACTIVE', ?, ?)
			ON CONFLICT (wallet_id) DO NOTHING
			""";

	private static final String CREDIT_WALLET_SQL = """
			UPDATE wallet_balances
			SET balance = balance + ?, aggregate_version = GREATEST(aggregate_version, ?), updated_at = ?
			WHERE wallet_id = ?
			""";

	private static final String DEBIT_WALLET_SQL = """
			UPDATE wallet_balances
			SET balance = balance - ?, aggregate_version = GREATEST(aggregate_version, ?), updated_at = ?
			WHERE wallet_id = ? AND balance >= ?
			""";

	private static final String LOCK_WALLET_SQL = """
			UPDATE wallet_balances
			SET status = 'LOCKED', aggregate_version = GREATEST(aggregate_version, ?), updated_at = ?
			WHERE wallet_id = ?
			""";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final String consumerGroup;

	@Autowired
	public BalanceProjector(JdbcTemplate jdbc, TransactionTemplate tx,
			@Value("${query.consumer.balance-group-id:" + DEFAULT_CONSUMER_GROUP + "}") String consumerGroup) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
		this.tx = Objects.requireNonNull(tx, "tx");
		Objects.requireNonNull(consumerGroup, "consumerGroup");
		if (consumerGroup.isBlank() || consumerGroup.length() > MAX_CONSUMER_GROUP_LENGTH) {
			throw new IllegalArgumentException(
					"consumerGroup must be 1.." + MAX_CONSUMER_GROUP_LENGTH + " non-blank characters");
		}
		this.consumerGroup = consumerGroup;
	}

	public BalanceProjector(JdbcTemplate jdbc, TransactionTemplate tx) {
		this(jdbc, tx, DEFAULT_CONSUMER_GROUP);
	}

	public String consumerGroup() {
		return consumerGroup;
	}

	public boolean project(UUID eventId, DomainEvent event, long aggregateVersion, Instant occurredAt) {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(event, "event");
		Objects.requireNonNull(occurredAt, "occurredAt");
		if (aggregateVersion < 1) {
			throw new IllegalArgumentException("aggregateVersion must be >= 1, got " + aggregateVersion);
		}
		validateEvent(event);

		Boolean applied = tx.execute(status -> {
			if (jdbc.update(INSERT_PROCESSED_SQL, consumerGroup, eventId) == 0) {
				return false;
			}
			OffsetDateTime ts = OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC);
			switch (event) {
				case WalletOpened e -> jdbc.update(INSERT_WALLET_SQL, e.walletId(), e.ownerId(),
						e.currency().name(), aggregateVersion, ts);
				case WalletCredited e -> {
					int updated = jdbc.update(CREDIT_WALLET_SQL, e.amount().amount(), aggregateVersion, ts,
							e.walletId());
					if (updated == 0) {
						throw new IllegalStateException("Wallet balance row missing for credit: " + e.walletId());
					}
				}
				case WalletDebited e -> {
					int updated = jdbc.update(DEBIT_WALLET_SQL, e.amount().amount(), aggregateVersion, ts,
							e.walletId(), e.amount().amount());
					if (updated == 0) {
						throw new IllegalStateException(
								"Wallet balance row missing or insufficient for debit: " + e.walletId());
					}
				}
				case WalletLocked e -> {
					int updated = jdbc.update(LOCK_WALLET_SQL, aggregateVersion, ts, e.walletId());
					if (updated == 0) {
						throw new IllegalStateException("Wallet balance row missing for lock: " + e.walletId());
					}
				}
				default -> throw new IllegalArgumentException(
						"Unsupported balance event: " + event.getClass().getName());
			}
			return true;
		});
		return Boolean.TRUE.equals(applied);
	}

	private static void validateEvent(DomainEvent event) {
		switch (event) {
			case WalletOpened e -> {
				if (e.walletId() == null || e.ownerId() == null || e.currency() != Currency.VND) {
					throw new IllegalArgumentException("Invalid WalletOpened event: " + e);
				}
			}
			case WalletCredited e -> validateMovement(e.walletId(), e.amount());
			case WalletDebited e -> validateMovement(e.walletId(), e.amount());
			case WalletLocked e -> {
				if (e.walletId() == null) {
					throw new IllegalArgumentException("WalletLocked.walletId must not be null");
				}
			}
			default -> throw new IllegalArgumentException(
					"Unsupported balance event: " + event.getClass().getName());
		}
	}

	private static void validateMovement(UUID walletId, Money amount) {
		if (walletId == null || amount == null || !amount.isPositive() || amount.currency() != Currency.VND) {
			throw new IllegalArgumentException("Invalid wallet movement for balance projection");
		}
	}
}
