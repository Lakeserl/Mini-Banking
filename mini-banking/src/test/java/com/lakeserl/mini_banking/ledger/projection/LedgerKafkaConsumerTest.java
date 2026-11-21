package com.lakeserl.mini_banking.ledger.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.EventSerializer;
import com.lakeserl.mini_banking.wallet.domain.events.WalletCredited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletDebited;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletEventTypes;

class LedgerKafkaConsumerTest {

	private static final Instant OCCURRED_AT = Instant.parse("2026-11-19T10:30:00.123456Z");

	private EventSerializer serializer;
	private LedgerProjector projector;
	private LedgerKafkaConsumer consumer;

	@BeforeEach
	void setUp() {
		serializer = new EventSerializer(List.of(new WalletEventTypes()));
		projector = mock(LedgerProjector.class);
		consumer = new LedgerKafkaConsumer(serializer, projector);
	}

	@Test
	void onWalletEventDeserializesWalletCreditedAndProjectsWithExactOccurredAt() {
		UUID eventId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		UUID txId = UUID.randomUUID();
		WalletCredited event = new WalletCredited(
				walletId, Money.vnd(500_000L), txId, "BANK_SETTLEMENT", AccountType.SETTLEMENT);
		ConsumerRecord<String, String> record = recordWithHeaders(
				walletId.toString(),
				serializer.toJson(event),
				eventId.toString(),
				"WalletCredited",
				OCCURRED_AT.toString());

		consumer.onWalletEvent(record);

		verify(projector).project(eventId, event, OCCURRED_AT);
	}

	@Test
	void onWalletEventDeserializesWalletDebitedAndProjectsWithExactOccurredAt() {
		UUID eventId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		UUID txId = UUID.randomUUID();
		WalletDebited event = new WalletDebited(
				walletId, Money.vnd(250_000L), txId, "TRANSFER_CLEARING", AccountType.CLEARING);
		ConsumerRecord<String, String> record = recordWithHeaders(
				walletId.toString(),
				serializer.toJson(event),
				eventId.toString(),
				"WalletDebited",
				OCCURRED_AT.toString());

		consumer.onWalletEvent(record);

		verify(projector).project(eventId, event, OCCURRED_AT);
	}

	@Test
	void onWalletEventRejectsMissingEventIdHeader() {
		ConsumerRecord<String, String> record = recordWithHeaders(
				"wallet-1", "{}", null, "WalletCredited", OCCURRED_AT.toString());

		assertThatThrownBy(() -> consumer.onWalletEvent(record))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("event-id");
		verifyNoInteractions(projector);
	}

	@Test
	void onWalletEventRejectsMissingEventTypeHeader() {
		ConsumerRecord<String, String> record = recordWithHeaders(
				"wallet-1", "{}", UUID.randomUUID().toString(), null, OCCURRED_AT.toString());

		assertThatThrownBy(() -> consumer.onWalletEvent(record))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("event-type");
		verifyNoInteractions(projector);
	}

	@Test
	void onWalletEventRejectsMissingOccurredAtHeader() {
		ConsumerRecord<String, String> record = recordWithHeaders(
				"wallet-1", "{}", UUID.randomUUID().toString(), "WalletCredited", null);

		assertThatThrownBy(() -> consumer.onWalletEvent(record))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("occurred-at");
		verifyNoInteractions(projector);
	}

	@Test
	void onWalletEventRejectsMalformedUuidAndOccurredAtHeaders() {
		ConsumerRecord<String, String> badUuid = recordWithHeaders(
				"wallet-1", "{}", "not-a-uuid", "WalletCredited", OCCURRED_AT.toString());
		ConsumerRecord<String, String> badInstant = recordWithHeaders(
				"wallet-1", "{}", UUID.randomUUID().toString(), "WalletCredited", "not-an-instant");

		assertThatThrownBy(() -> consumer.onWalletEvent(badUuid))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("event-id");
		assertThatThrownBy(() -> consumer.onWalletEvent(badInstant))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("occurred-at");
		verifyNoInteractions(projector);
	}

	@Test
	void onWalletEventRejectsMalformedJsonPayload() {
		ConsumerRecord<String, String> record = recordWithHeaders(
				"wallet-1", "{not-valid-json", UUID.randomUUID().toString(), "WalletCredited", OCCURRED_AT.toString());

		assertThatThrownBy(() -> consumer.onWalletEvent(record))
				.isInstanceOf(RuntimeException.class);
		verifyNoInteractions(projector);
	}

	@Test
	void conditionalPropertyDisablesLedgerKafkaConsumerAndErrorHandler() {
		new ApplicationContextRunner()
				.withPropertyValues("ledger.consumer.enabled=false", "query.consumer.enabled=false")
				.withBean(EventSerializer.class, () -> serializer)
				.withBean(LedgerProjector.class, () -> projector)
				.withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
				.withUserConfiguration(LedgerKafkaConfig.class, LedgerKafkaConsumer.class)
				.run(context -> {
					assertThat(context).doesNotHaveBean(LedgerKafkaConsumer.class);
					assertThat(context).doesNotHaveBean(CommonErrorHandler.class);
				});
	}

	private static ConsumerRecord<String, String> recordWithHeaders(
			String key, String value, String eventId, String eventType, String occurredAt) {
		ConsumerRecord<String, String> record = new ConsumerRecord<>("wallet.events", 0, 0L, key, value);
		if (eventId != null) {
			record.headers().add("event-id", eventId.getBytes(StandardCharsets.UTF_8));
		}
		if (eventType != null) {
			record.headers().add("event-type", eventType.getBytes(StandardCharsets.UTF_8));
		}
		if (occurredAt != null) {
			record.headers().add("occurred-at", occurredAt.getBytes(StandardCharsets.UTF_8));
		}
		return record;
	}

	@Nested
	@SpringBootTest(
			classes = {
					KafkaAutoConfiguration.class,
					LedgerKafkaConfig.class,
					LedgerKafkaConsumer.class,
					EventSerializer.class,
					WalletEventTypes.class
			},
			properties = {
					"spring.kafka.consumer.auto-offset-reset=earliest",
					"ledger.consumer.enabled=true",
					"ledger.consumer.group-id=ledger-projector-embedded-test",
					"ledger.consumer.retry-backoff-ms=10"
			})
	@EmbeddedKafka(
			partitions = 1,
			topics = { "wallet.events", "wallet.events.DLT" },
			bootstrapServersProperty = "spring.kafka.bootstrap-servers")
	@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
	class EmbeddedKafkaIntegrationTest {

		@Autowired
		EmbeddedKafkaBroker embeddedKafka;

		@Autowired
		KafkaTemplate<String, String> kafkaTemplate;

		@MockitoSpyBean
		EventSerializer spySerializer;

		@MockitoBean
		LedgerProjector mockProjector;

		@BeforeEach
		void resetMocks() {
			reset(spySerializer, mockProjector);
		}

		@Test
		void validWalletEventMessageIsConsumedAndProjectedWithExactOccurredAt() throws Exception {
			UUID eventId = UUID.randomUUID();
			UUID walletId = UUID.randomUUID();
			UUID txId = UUID.randomUUID();
			Instant occurredAt = Instant.parse("2026-11-19T12:00:00.654321Z");
			WalletCredited expected = new WalletCredited(
					walletId, Money.vnd(750_000L), txId, "BANK_SETTLEMENT", AccountType.SETTLEMENT);
			String json = spySerializer.toJson(expected);

			ProducerRecord<String, String> record = new ProducerRecord<>("wallet.events", walletId.toString(), json);
			record.headers().add("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8));
			record.headers().add("event-type", "WalletCredited".getBytes(StandardCharsets.UTF_8));
			record.headers().add("occurred-at", occurredAt.toString().getBytes(StandardCharsets.UTF_8));

			kafkaTemplate.send(record).get();

			verify(mockProjector, timeout(10_000)).project(eventId, expected, occurredAt);
		}

		@Test
		void poisonMalformedJsonRetriesThreeTimesAndLandsOnDlt() throws Exception {
			UUID eventId = UUID.randomUUID();
			UUID walletId = UUID.randomUUID();
			String poisonPayload = "{malformed-json-payload";

			ProducerRecord<String, String> poison = new ProducerRecord<>(
					"wallet.events", walletId.toString(), poisonPayload);
			poison.headers().add("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8));
			poison.headers().add("event-type", "WalletCredited".getBytes(StandardCharsets.UTF_8));
			poison.headers().add("occurred-at", OCCURRED_AT.toString().getBytes(StandardCharsets.UTF_8));

			try (Consumer<String, String> dltConsumer = createDltConsumer("dlt-verify-poison-" + UUID.randomUUID())) {
				kafkaTemplate.send(poison).get();

				// 1 initial attempt + 3 retries = 4 total invocations before DLT
				verify(spySerializer, timeout(10_000).times(4)).deserialize("WalletCredited", poisonPayload);
				verifyNoInteractions(mockProjector);

				ConsumerRecord<String, String> dltRecord = awaitDltRecordByEventId(
						dltConsumer, eventId, Duration.ofSeconds(10));
				assertThat(dltRecord.topic()).isEqualTo("wallet.events.DLT");
				assertThat(dltRecord.key()).isEqualTo(walletId.toString());
				assertThat(dltRecord.value()).isEqualTo(poisonPayload);
				assertThat(new String(dltRecord.headers().lastHeader("event-id").value(), StandardCharsets.UTF_8))
						.isEqualTo(eventId.toString());
				assertThat(new String(dltRecord.headers().lastHeader("occurred-at").value(), StandardCharsets.UTF_8))
						.isEqualTo(OCCURRED_AT.toString());
			}
		}

		@Test
		void projectorFailureRetriesThreeTimesAndLandsOnDlt() throws Exception {
			UUID eventId = UUID.randomUUID();
			UUID walletId = UUID.randomUUID();
			UUID txId = UUID.randomUUID();
			WalletDebited event = new WalletDebited(
					walletId, Money.vnd(100_000L), txId, "BANK_SETTLEMENT", AccountType.SETTLEMENT);
			String json = spySerializer.toJson(event);

			when(mockProjector.project(eq(eventId), eq(event), eq(OCCURRED_AT)))
					.thenThrow(new RuntimeException("simulated transient db failure"));

			ProducerRecord<String, String> record = new ProducerRecord<>("wallet.events", walletId.toString(), json);
			record.headers().add("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8));
			record.headers().add("event-type", "WalletDebited".getBytes(StandardCharsets.UTF_8));
			record.headers().add("occurred-at", OCCURRED_AT.toString().getBytes(StandardCharsets.UTF_8));

			try (Consumer<String, String> dltConsumer = createDltConsumer("dlt-verify-fail-" + UUID.randomUUID())) {
				kafkaTemplate.send(record).get();

				verify(mockProjector, timeout(10_000).times(4)).project(eventId, event, OCCURRED_AT);

				ConsumerRecord<String, String> dltRecord = awaitDltRecordByEventId(
						dltConsumer, eventId, Duration.ofSeconds(10));
				assertThat(dltRecord.topic()).isEqualTo("wallet.events.DLT");
				assertThat(dltRecord.key()).isEqualTo(walletId.toString());
				assertThat(dltRecord.value()).isEqualTo(json);
				assertThat(new String(dltRecord.headers().lastHeader("event-id").value(), StandardCharsets.UTF_8))
						.isEqualTo(eventId.toString());
			}
		}

		private ConsumerRecord<String, String> awaitDltRecordByEventId(
				Consumer<String, String> dltConsumer, UUID expectedEventId, Duration timeoutDuration) {
			Instant deadline = Instant.now().plus(timeoutDuration);
			while (Instant.now().isBefore(deadline)) {
				for (ConsumerRecord<String, String> candidate : dltConsumer.poll(Duration.ofMillis(200))) {
					var header = candidate.headers().lastHeader("event-id");
					if (header != null
							&& expectedEventId.toString().equals(new String(header.value(), StandardCharsets.UTF_8))) {
						return candidate;
					}
				}
			}
			throw new AssertionError("Timed out waiting for DLT record with event-id=" + expectedEventId);
		}

		private Consumer<String, String> createDltConsumer(String groupId) {
			Map<String, Object> props = KafkaTestUtils.consumerProps(embeddedKafka, groupId, true);
			props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
			props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
			props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
			Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<String, String>(props).createConsumer();
			embeddedKafka.consumeFromAnEmbeddedTopic(consumer, "wallet.events.DLT");
			return consumer;
		}
	}
}
