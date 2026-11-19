package com.lakeserl.mini_banking.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
		OutboxMessage msg = new OutboxMessage(1L, eventId, aggregateId, "Wallet", "WalletCredited", "{\"amount\":100}");
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
	}

	@Test
	@SuppressWarnings("unchecked")
	void publishWrapsSendFailureInOutboxPublishException() {
		OutboxMessage msg = new OutboxMessage(2L, UUID.randomUUID(), UUID.randomUUID(), "Wallet", "WalletOpened", "{}");
		CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
		failed.completeExceptionally(new RuntimeException("broker down"));
		when(kafka.send(any(ProducerRecord.class))).thenReturn(failed);

		assertThatThrownBy(() -> publisher.publish(msg))
				.isInstanceOf(OutboxPublishException.class);
	}
}
