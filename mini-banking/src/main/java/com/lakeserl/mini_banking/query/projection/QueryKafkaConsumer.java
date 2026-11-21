package com.lakeserl.mini_banking.query.projection;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.eventsourcing.EventSerializer;

/**
 * Consumes domain events from Kafka and delegates idempotently to {@link BalanceProjector}
 * and {@link TransactionHistoryProjector}. Reuses the shared 3x retry + {@code .DLT} error handler.
 */
@Component
@ConditionalOnProperty(name = "query.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class QueryKafkaConsumer {

	static final String HEADER_EVENT_ID = "event-id";
	static final String HEADER_EVENT_TYPE = "event-type";
	static final String HEADER_OCCURRED_AT = "occurred-at";
	static final String HEADER_AGGREGATE_VERSION = "aggregate-version";

	private final EventSerializer serializer;
	private final BalanceProjector balanceProjector;
	private final TransactionHistoryProjector txHistoryProjector;

	public QueryKafkaConsumer(EventSerializer serializer, BalanceProjector balanceProjector,
			TransactionHistoryProjector txHistoryProjector) {
		this.serializer = Objects.requireNonNull(serializer, "serializer");
		this.balanceProjector = Objects.requireNonNull(balanceProjector, "balanceProjector");
		this.txHistoryProjector = Objects.requireNonNull(txHistoryProjector, "txHistoryProjector");
	}

	@KafkaListener(
			id = "balance-projector-listener",
			topics = "wallet.events",
			groupId = "${query.consumer.balance-group-id:balance-projector}")
	public void onWalletEvent(ConsumerRecord<String, String> record) {
		Objects.requireNonNull(record, "record");
		UUID eventId = parseUuidHeader(record, HEADER_EVENT_ID);
		String eventType = requireHeader(record, HEADER_EVENT_TYPE);
		Instant occurredAt = parseInstantHeader(record, HEADER_OCCURRED_AT);
		long aggregateVersion = parsePositiveLongHeader(record, HEADER_AGGREGATE_VERSION);
		DomainEvent event = deserialize(eventType, record.value());
		balanceProjector.project(eventId, event, aggregateVersion, occurredAt);
	}

	@KafkaListener(
			id = "tx-history-projector-listener",
			topics = { "deposit.events", "withdrawal.events", "transfer.events" },
			groupId = "${query.consumer.tx-history-group-id:tx-history-projector}")
	public void onTransactionEvent(ConsumerRecord<String, String> record) {
		Objects.requireNonNull(record, "record");
		UUID eventId = parseUuidHeader(record, HEADER_EVENT_ID);
		String eventType = requireHeader(record, HEADER_EVENT_TYPE);
		Instant occurredAt = parseInstantHeader(record, HEADER_OCCURRED_AT);
		DomainEvent event = deserialize(eventType, record.value());
		txHistoryProjector.project(eventId, event, occurredAt);
	}

	private DomainEvent deserialize(String eventType, String payload) {
		if (payload == null || payload.isBlank()) {
			throw new IllegalArgumentException("Kafka record value must not be null or blank");
		}
		return serializer.deserialize(eventType, payload);
	}

	private static String requireHeader(ConsumerRecord<String, String> record, String headerName) {
		Header header = record.headers().lastHeader(headerName);
		if (header == null || header.value() == null) {
			throw new IllegalArgumentException("Missing required Kafka header: " + headerName);
		}
		String value = new String(header.value(), StandardCharsets.UTF_8);
		if (value.isBlank()) {
			throw new IllegalArgumentException("Blank required Kafka header: " + headerName);
		}
		return value;
	}

	private static UUID parseUuidHeader(ConsumerRecord<String, String> record, String headerName) {
		String raw = requireHeader(record, headerName);
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Malformed UUID in Kafka header " + headerName + ": " + raw, e);
		}
	}

	private static Instant parseInstantHeader(ConsumerRecord<String, String> record, String headerName) {
		String raw = requireHeader(record, headerName);
		try {
			return Instant.parse(raw);
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("Malformed ISO-8601 Instant in Kafka header " + headerName + ": " + raw, e);
		}
	}

	private static long parsePositiveLongHeader(ConsumerRecord<String, String> record, String headerName) {
		String raw = requireHeader(record, headerName);
		try {
			long parsed = Long.parseLong(raw);
			if (parsed < 1) {
				throw new IllegalArgumentException("Header " + headerName + " must be >= 1: " + raw);
			}
			return parsed;
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Malformed long in Kafka header " + headerName + ": " + raw, e);
		}
	}
}
