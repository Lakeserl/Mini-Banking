package com.lakeserl.mini_banking.shared.outbox;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publishes to topic {@code <aggregateType lower-case>.events}, keyed by aggregate id to keep per-aggregate order. */
@Component
public class KafkaOutboxPublisher implements OutboxPublisher {

	private static final long SEND_TIMEOUT_SECONDS = 5;

	private final KafkaTemplate<String, String> kafka;

	public KafkaOutboxPublisher(KafkaTemplate<String, String> kafka) {
		this.kafka = kafka;
	}

	@Override
	public void publish(OutboxMessage message) {
		ProducerRecord<String, String> record = new ProducerRecord<>(
				message.aggregateType().toLowerCase() + ".events", message.aggregateId().toString(),
				message.payload());
		record.headers().add("event-type", message.eventType().getBytes(StandardCharsets.UTF_8));
		record.headers().add("event-id", message.eventId().toString().getBytes(StandardCharsets.UTF_8));
		try {
			kafka.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new OutboxPublishException("Interrupted while publishing outbox message " + message.id(), e);
		} catch (ExecutionException | TimeoutException e) {
			throw new OutboxPublishException("Failed to publish outbox message " + message.id(), e);
		}
	}
}
