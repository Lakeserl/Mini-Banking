package com.lakeserl.mini_banking.ledger.projection;

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

import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.wallet.domain.events.WalletCredited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletDebited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletLocked;
import com.lakeserl.mini_banking.wallet.domain.events.WalletOpened;

/**
 * Idempotent double-entry projector transforming wallet domain events into balanced
 * {@code ledger_entries} pairs within a single transaction alongside {@code processed_events}.
 */
@Service
public class LedgerProjector {

	public static final String DEFAULT_CONSUMER_GROUP = "ledger-projector";

	private static final String ENTRY_DEBIT = "DEBIT";
	private static final String ENTRY_CREDIT = "CREDIT";

	private static final String INSERT_PROCESSED_EVENT_SQL = """
			INSERT INTO processed_events (consumer_group, event_id, processed_at)
			VALUES (?, ?, now())
			ON CONFLICT (consumer_group, event_id) DO NOTHING
			""";

	private static final String INSERT_LEDGER_ENTRY_SQL = """
			INSERT INTO ledger_entries
				(transaction_id, account_id, account_type, entry_type, amount, currency, occurred_at, event_id)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?)
			""";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final String consumerGroup;

	@Autowired
	public LedgerProjector(JdbcTemplate jdbc, TransactionTemplate tx,
			@Value("${ledger.consumer.group-id:" + DEFAULT_CONSUMER_GROUP + "}") String consumerGroup) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
		this.tx = Objects.requireNonNull(tx, "tx");
		Objects.requireNonNull(consumerGroup, "consumerGroup");
		if (consumerGroup.isBlank()) {
			throw new IllegalArgumentException("consumerGroup must not be blank");
		}
		this.consumerGroup = consumerGroup;
	}

	public LedgerProjector(JdbcTemplate jdbc, TransactionTemplate tx) {
		this(jdbc, tx, DEFAULT_CONSUMER_GROUP);
	}

	/**
	 * Applies a single domain event to the ledger projection atomically.
	 *
	 * @return {@code true} if newly applied; {@code false} if already processed for this consumer group.
	 */
	public boolean project(UUID eventId, DomainEvent event, Instant occurredAt) {
		Objects.requireNonNull(eventId, "eventId");
		Objects.requireNonNull(event, "event");
		Objects.requireNonNull(occurredAt, "occurredAt");

		Boolean applied = tx.execute(status -> {
			validateEvent(event);

			int inserted = jdbc.update(INSERT_PROCESSED_EVENT_SQL, consumerGroup, eventId);
			if (inserted == 0) {
				return false;
			}

			OffsetDateTime ts = OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC);
			switch (event) {
				case WalletCredited e -> {
					insertEntry(e.transactionId(), e.counterpartAccountId(), e.counterpartAccountType().name(),
							ENTRY_DEBIT, e.amount(), ts, eventId);
					insertEntry(e.transactionId(), e.walletId().toString(), AccountType.USER_WALLET.name(),
							ENTRY_CREDIT, e.amount(), ts, eventId);
				}
				case WalletDebited e -> {
					insertEntry(e.transactionId(), e.walletId().toString(), AccountType.USER_WALLET.name(),
							ENTRY_DEBIT, e.amount(), ts, eventId);
					insertEntry(e.transactionId(), e.counterpartAccountId(), e.counterpartAccountType().name(),
							ENTRY_CREDIT, e.amount(), ts, eventId);
				}
				case WalletOpened ignored -> {
					// Non-monetary lifecycle event: recorded in processed_events with 0 ledger rows.
				}
				case WalletLocked ignored -> {
					// Non-monetary lifecycle event: recorded in processed_events with 0 ledger rows.
				}
				default -> throw new IllegalArgumentException(
						"Unsupported ledger event: " + event.getClass().getName());
			}
			return true;
		});
		return Boolean.TRUE.equals(applied);
	}

	private void insertEntry(UUID transactionId, String accountId, String accountType, String entryType,
			Money amount, OffsetDateTime occurredAt, UUID eventId) {
		jdbc.update(INSERT_LEDGER_ENTRY_SQL,
				transactionId,
				accountId,
				accountType,
				entryType,
				amount.amount(),
				amount.currency().name(),
				occurredAt,
				eventId);
	}

	private static void validateEvent(DomainEvent event) {
		switch (event) {
			case WalletCredited e -> validateMovement(e.walletId(), e.amount(), e.transactionId(),
					e.counterpartAccountId(), e.counterpartAccountType());
			case WalletDebited e -> validateMovement(e.walletId(), e.amount(), e.transactionId(),
					e.counterpartAccountId(), e.counterpartAccountType());
			case WalletOpened ignored -> {
			}
			case WalletLocked ignored -> {
			}
			default -> throw new IllegalArgumentException(
					"Unsupported ledger event: " + event.getClass().getName());
		}
	}

	private static void validateMovement(UUID walletId, Money amount, UUID transactionId,
			String counterpartAccountId, AccountType counterpartAccountType) {
		if (walletId == null) {
			throw new IllegalArgumentException("walletId must not be null");
		}
		if (transactionId == null) {
			throw new IllegalArgumentException("transactionId must not be null");
		}
		if (amount == null) {
			throw new IllegalArgumentException("amount must not be null");
		}
		if (!amount.isPositive()) {
			throw new IllegalArgumentException("Ledger amount must be strictly positive, got: " + amount.amount());
		}
		if (amount.currency() != Currency.VND) {
			throw new IllegalArgumentException("Ledger only supports VND currency, got: " + amount.currency());
		}
		if (counterpartAccountId == null || counterpartAccountId.isBlank()) {
			throw new IllegalArgumentException("counterpartAccountId must not be blank");
		}
		if (counterpartAccountType == null) {
			throw new IllegalArgumentException("counterpartAccountType must not be null");
		}
	}
}
