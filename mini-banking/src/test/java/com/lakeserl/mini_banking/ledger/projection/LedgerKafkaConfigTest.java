package com.lakeserl.mini_banking.ledger.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;

class LedgerKafkaConfigTest {

	@Test
	@SuppressWarnings("unchecked")
	void ledgerKafkaErrorHandlerPublishesToDltWithNullPartitionSoBrokerPartitionerChoosesValidPartition() {
		KafkaTemplate<Object, Object> kafkaTemplate = mock(KafkaTemplate.class);
		when(kafkaTemplate.send(any(ProducerRecord.class)))
				.thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
		CommonErrorHandler errorHandler = new LedgerKafkaConfig().ledgerKafkaErrorHandler(kafkaTemplate, 0L);

		ConsumerRecord<String, String> sourceRecord = new ConsumerRecord<>(
				"wallet.events", 3, 42L, "wallet-key", "{\"bad\":true}");
		Consumer<?, ?> consumer = mock(Consumer.class);
		MessageListenerContainer container = mock(MessageListenerContainer.class);
		RuntimeException failure = new RuntimeException("simulated projection failure");

		for (long attempt = 1; attempt <= LedgerKafkaConfig.MAX_RETRIES; attempt++) {
			assertThatThrownBy(() -> errorHandler.handleRemaining(
					failure, List.of(sourceRecord), consumer, container))
					.isInstanceOf(Exception.class);
		}
		errorHandler.handleRemaining(failure, List.of(sourceRecord), consumer, container);

		ArgumentCaptor<ProducerRecord<Object, Object>> recordCaptor = ArgumentCaptor.forClass(ProducerRecord.class);
		verify(kafkaTemplate).send(recordCaptor.capture());
		verify(kafkaTemplate, never()).partitionsFor(anyString());
		ProducerRecord<Object, Object> dltRecord = recordCaptor.getValue();
		assertThat(dltRecord.topic()).isEqualTo("wallet.events.DLT");
		assertThat(dltRecord.partition()).isNull();
		assertThat(dltRecord.key()).isEqualTo("wallet-key");
		assertThat(LedgerKafkaConfig.resolveDltDestination(sourceRecord, failure))
				.isEqualTo(new TopicPartition("wallet.events.DLT", -1));
	}

	@Test
	void ledgerKafkaErrorHandlerRejectsNegativeRetryBackoff() {
		assertThatThrownBy(() -> new LedgerKafkaConfig().ledgerKafkaErrorHandler(mock(KafkaTemplate.class), -1L))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("ledger.consumer.retry-backoff-ms");
	}
}
