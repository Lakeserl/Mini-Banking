package com.lakeserl.mini_banking.ledger.projection;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Configures 3x retry + dead-letter topic ({@code <topic>.DLT}) recovery for projection Kafka consumers
 * while preserving Spring Boot's auto-configured {@code kafkaListenerContainerFactory}.
 */
@Configuration
@ConditionalOnExpression("${ledger.consumer.enabled:true} or ${query.consumer.enabled:true}")
public class LedgerKafkaConfig {

	static final long MAX_RETRIES = 3L;
	static final String DLT_SUFFIX = ".DLT";

	@Bean
	CommonErrorHandler ledgerKafkaErrorHandler(KafkaTemplate<?, ?> kafkaTemplate,
			@Value("${ledger.consumer.retry-backoff-ms:1000}") long retryBackoffMs) {
		if (retryBackoffMs < 0) {
			throw new IllegalArgumentException("ledger.consumer.retry-backoff-ms must not be negative");
		}
		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
				kafkaTemplate,
				(record, ex) -> new TopicPartition(record.topic() + DLT_SUFFIX, record.partition()));
		return new DefaultErrorHandler(recoverer, new FixedBackOff(retryBackoffMs, MAX_RETRIES));
	}
}
