package com.lakeserl.mini_banking.ledger.projection;

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
 * Consumes wallet domain events from {@code wallet.events} and projects them idempotently
 * into double-entry ledger rows via {@link LedgerProjector}.
 */
@Component
@ConditionalOnProperty(name = "ledger.consumer.enabled", havingValue = "true", matchIfMissing = true)
public class LedgerKafkaConsumer {

	static final String HEADER_EVENT_ID = "event-id";
	static final String HEADER_EVENT_TYPE = "event-type";
	static final String HEADER_OCCURRED_AT = "occurred-at";

	private final EventSerializer serializer;
	private final LedgerProjector projector;

	public LedgerKafkaConsumer(EventSerializer serializer, LedgerProjector projector) {
		this.serializer = Objects.requireNonNull(serializer, "serializer");
		this.projector = Objects.requireNonNull(projector, "projector");
	}

	@KafkaListener(topics = "wallet.events", groupId = "${ledger.consumer.group-id:ledger-projector}")
	public void onWalletEvent(ConsumerRecord<String, String> record) {
		Objects.requireNonNull(record, "record");
		UUID eventId = parseUuidHeader(record, HEADER_EVENT_ID);
		String eventType = requireHeader(record, HEADER_EVENT_TYPE);
		Instant occurredAt = parseInstantHeader(record, HEADER_OCCURRED_AT);

		String payload = record.value();
		if (payload == null || payload.isBlank()) {
			throw new IllegalArgumentException("Kafka record value must not be null or blank");
		}

		DomainEvent event = serializer.deserialize(eventType, payload);
		projector.project(eventId, event, occurredAt);
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
}
