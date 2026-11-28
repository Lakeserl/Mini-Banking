package com.lakeserl.mini_banking.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InterruptException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class KafkaOutboxPublisherTest {

	@SuppressWarnings("unchecked")
	private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
	private final KafkaOutboxPublisher publisher = new KafkaOutboxPublisher(kafka);

	@Test
	@SuppressWarnings("unchecked")
	void publishSendsToAggregateTopicWithKeyAndHeaders() {
		UUID eventId = UUID.randomUUID();
		UUID aggregateId = UUID.randomUUID();
		Instant occurredAt = Instant.parse("2026-11-19T10:30:00.123456Z");
		OutboxMessage msg = new OutboxMessage(1L, eventId, aggregateId, "Wallet", "WalletCredited", "{\"amount\":100}",
				occurredAt, 7L);
		when(kafka.send(any(ProducerRecord.class)))
				.thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

		publisher.publish(msg);

		ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
		verify(kafka).send(captor.capture());
		ProducerRecord<String, String> record = captor.getValue();

		assertThat(record.topic()).isEqualTo("wallet.events");
		assertThat(record.key()).isEqualTo(aggregateId.toString());
		assertThat(record.value()).isEqualTo("{\"amount\":100}");
		assertThat(new String(record.headers().lastHeader("event-type").value(), StandardCharsets.UTF_8))
				.isEqualTo("WalletCredited");
		assertThat(new String(record.headers().lastHeader("event-id").value(), StandardCharsets.UTF_8))
				.isEqualTo(eventId.toString());
		assertThat(new String(record.headers().lastHeader("occurred-at").value(), StandardCharsets.UTF_8))
				.isEqualTo(occurredAt.toString());
		assertThat(new String(record.headers().lastHeader("aggregate-version").value(), StandardCharsets.UTF_8))
				.isEqualTo("7");
	}

	@Test
	@SuppressWarnings("unchecked")
	void publishOmitsAggregateVersionHeaderWhenNull() {
		OutboxMessage msg = new OutboxMessage(4L, UUID.randomUUID(), UUID.randomUUID(), "Wallet", "WalletOpened", "{}",
				Instant.parse("2026-11-19T10:30:00Z"));
		when(kafka.send(any(ProducerRecord.class)))
				.thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

		publisher.publish(msg);

		ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
		verify(kafka).send(captor.capture());
		assertThat(captor.getValue().headers().lastHeader("aggregate-version")).isNull();
	}

	@Test
	@SuppressWarnings("unchecked")
	void publishWrapsSendFailureInOutboxPublishException() {
		OutboxMessage msg = new OutboxMessage(2L, UUID.randomUUID(), UUID.randomUUID(), "Wallet", "WalletOpened", "{}",
				Instant.parse("2026-11-19T10:30:00Z"));
		CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
		failed.completeExceptionally(new RuntimeException("broker down"));
		when(kafka.send(any(ProducerRecord.class))).thenReturn(failed);

		assertThatThrownBy(() -> publisher.publish(msg))
				.isInstanceOf(OutboxPublishException.class);
	}

	@Test
	@SuppressWarnings("unchecked")
	void publishWrapsSynchronousKafkaExceptionInOutboxPublishException() {
		OutboxMessage msg = new OutboxMessage(5L, UUID.randomUUID(), UUID.randomUUID(), "Wallet", "WalletOpened", "{}",
				Instant.parse("2026-11-19T10:30:00Z"));
		KafkaException syncFailure = new KafkaException("metadata timeout");
		when(kafka.send(any(ProducerRecord.class))).thenThrow(syncFailure);

		assertThatThrownBy(() -> publisher.publish(msg))
				.isInstanceOf(OutboxPublishException.class)
				.hasCause(syncFailure);
	}

	@Test
	@SuppressWarnings("unchecked")
	void publishRestoresInterruptFlagAndWrapsKafkaInterruptException() {
		OutboxMessage msg = new OutboxMessage(6L, UUID.randomUUID(), UUID.randomUUID(), "Wallet", "WalletOpened", "{}",
				Instant.parse("2026-11-19T10:30:00Z"));
		InterruptException interruptFailure = new InterruptException("interrupted");
		when(kafka.send(any(ProducerRecord.class))).thenThrow(interruptFailure);
		Thread.interrupted();
		try {
			assertThatThrownBy(() -> publisher.publish(msg))
					.isInstanceOf(OutboxPublishException.class)
					.hasCause(interruptFailure);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void publishRestoresInterruptFlagAndWrapsFutureInterruptedException() {
		OutboxMessage msg = new OutboxMessage(7L, UUID.randomUUID(), UUID.randomUUID(), "Wallet", "WalletOpened", "{}",
				Instant.parse("2026-11-19T10:30:00Z"));
		InterruptedException interrupted = new InterruptedException("thread interrupted");
		CompletableFuture<SendResult<String, String>> interruptedFuture = new CompletableFuture<>() {
			@Override
			public SendResult<String, String> get(long timeout, TimeUnit unit) throws InterruptedException {
				throw interrupted;
			}
		};
		when(kafka.send(any(ProducerRecord.class))).thenReturn(interruptedFuture);
		Thread.interrupted();
		try {
			assertThatThrownBy(() -> publisher.publish(msg))
					.isInstanceOf(OutboxPublishException.class)
					.hasCause(interrupted);
			assertThat(Thread.currentThread().isInterrupted()).isTrue();
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	void publishRejectsNullOccurredAt() {
		OutboxMessage msg = new OutboxMessage(3L, UUID.randomUUID(), UUID.randomUUID(), "Wallet", "WalletOpened", "{}",
				null);

		assertThatThrownBy(() -> publisher.publish(msg))
				.isInstanceOf(NullPointerException.class);
	}
}
