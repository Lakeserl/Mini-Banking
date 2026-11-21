package com.lakeserl.mini_banking.query.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.lakeserl.mini_banking.deposit.domain.events.DepositRequested;
import com.lakeserl.mini_banking.deposit.infrastructure.DepositEventTypes;
import com.lakeserl.mini_banking.ledger.projection.LedgerKafkaConfig;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.eventsourcing.EventSerializer;
import com.lakeserl.mini_banking.transfer.domain.TransferType;
import com.lakeserl.mini_banking.transfer.domain.events.TransferInitiated;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferEventTypes;
import com.lakeserl.mini_banking.wallet.domain.events.WalletOpened;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletEventTypes;
import com.lakeserl.mini_banking.withdrawal.infrastructure.WithdrawalEventTypes;

class QueryKafkaConsumerTest {

	private static final Instant OCCURRED_AT = Instant.parse("2026-11-21T10:30:00Z");

	private EventSerializer serializer;
	private BalanceProjector balanceProjector;
	private TransactionHistoryProjector txHistoryProjector;
	private QueryKafkaConsumer consumer;

	@BeforeEach
	void setUp() {
		serializer = new EventSerializer(List.of(
				new WalletEventTypes(), new DepositEventTypes(),
				new WithdrawalEventTypes(), new TransferEventTypes()));
		balanceProjector = mock(BalanceProjector.class);
		txHistoryProjector = mock(TransactionHistoryProjector.class);
		consumer = new QueryKafkaConsumer(serializer, balanceProjector, txHistoryProjector);
	}

	@Test
	void onWalletEventProjectsToBalanceProjectorWithAggregateVersion() {
		UUID eventId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		WalletOpened event = new WalletOpened(walletId, UUID.randomUUID(), Currency.VND);
		ConsumerRecord<String, String> record = recordWithHeaders("wallet.events", walletId.toString(),
				serializer.toJson(event), eventId.toString(), "WalletOpened", OCCURRED_AT.toString(), "1");

		consumer.onWalletEvent(record);

		verify(balanceProjector).project(eventId, event, 1L, OCCURRED_AT);
		verifyNoInteractions(txHistoryProjector);
	}

	@Test
	void onWalletEventRejectsMissingOrInvalidAggregateVersionHeader() {
		ConsumerRecord<String, String> missing = recordWithHeaders("wallet.events", "w1", "{}",
				UUID.randomUUID().toString(), "WalletOpened", OCCURRED_AT.toString(), null);
		assertThatThrownBy(() -> consumer.onWalletEvent(missing))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("aggregate-version");

		ConsumerRecord<String, String> invalid = recordWithHeaders("wallet.events", "w1", "{}",
				UUID.randomUUID().toString(), "WalletOpened", OCCURRED_AT.toString(), "not-a-number");
		assertThatThrownBy(() -> consumer.onWalletEvent(invalid))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("aggregate-version");
		verifyNoInteractions(balanceProjector);
	}

	@Test
	void onTransactionEventRoutesDepositAndTransferToTransactionHistoryProjector() {
		UUID depEventId = UUID.randomUUID();
		DepositRequested deposit = new DepositRequested(UUID.randomUUID(), UUID.randomUUID(), Money.vnd(100_000),
				"REF-1");
		consumer.onTransactionEvent(recordWithHeaders("deposit.events", deposit.depositId().toString(),
				serializer.toJson(deposit), depEventId.toString(), "DepositRequested", OCCURRED_AT.toString(), "1"));

		UUID trfEventId = UUID.randomUUID();
		TransferInitiated transfer = new TransferInitiated(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				Money.vnd(50_000), TransferType.STANDARD, null);
		consumer.onTransactionEvent(recordWithHeaders("transfer.events", transfer.transferId().toString(),
				serializer.toJson(transfer), trfEventId.toString(), "TransferInitiated", OCCURRED_AT.toString(), "1"));

		verify(txHistoryProjector).project(depEventId, deposit, OCCURRED_AT);
		verify(txHistoryProjector).project(trfEventId, transfer, OCCURRED_AT);
		verifyNoInteractions(balanceProjector);
	}

	private static ConsumerRecord<String, String> recordWithHeaders(String topic, String key, String payload,
			String eventId, String eventType, String occurredAt, String aggregateVersion) {
		ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, key, payload);
		if (eventId != null) {
			record.headers().add("event-id", eventId.getBytes(StandardCharsets.UTF_8));
		}
		if (eventType != null) {
			record.headers().add("event-type", eventType.getBytes(StandardCharsets.UTF_8));
		}
		if (occurredAt != null) {
			record.headers().add("occurred-at", occurredAt.getBytes(StandardCharsets.UTF_8));
		}
		if (aggregateVersion != null) {
			record.headers().add("aggregate-version", aggregateVersion.getBytes(StandardCharsets.UTF_8));
		}
		return record;
	}

	@Nested
	@SpringBootTest(classes = {
			KafkaAutoConfiguration.class,
			WalletEventTypes.class,
			DepositEventTypes.class,
			WithdrawalEventTypes.class,
			TransferEventTypes.class,
			EventSerializer.class,
			LedgerKafkaConfig.class,
			QueryKafkaConsumer.class
	}, properties = {
			"spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
			"spring.kafka.consumer.auto-offset-reset=earliest",
			"ledger.consumer.enabled=false",
			"ledger.consumer.retry-backoff-ms=10",
			"query.consumer.enabled=true",
			"query.consumer.balance-group-id=test-balance-projector",
			"query.consumer.tx-history-group-id=test-tx-history-projector"
	})
	@EmbeddedKafka(partitions = 1, topics = {
			"wallet.events", "wallet.events.DLT",
			"deposit.events", "deposit.events.DLT",
			"withdrawal.events", "withdrawal.events.DLT",
			"transfer.events", "transfer.events.DLT"
	})
	@DirtiesContext
	class EmbeddedKafkaRoutingAndDltTests {

		@Autowired
		KafkaTemplate<String, String> kafkaTemplate;

		@Autowired
		EventSerializer eventSerializer;

		@Autowired
		EmbeddedKafkaBroker embeddedKafkaBroker;

		@MockitoBean
		BalanceProjector mockBalanceProjector;

		@MockitoBean
		TransactionHistoryProjector mockTxHistoryProjector;

		@MockitoSpyBean
		QueryKafkaConsumer spyConsumer;

		@BeforeEach
		void resetMocks() {
			reset(mockBalanceProjector, mockTxHistoryProjector, spyConsumer);
		}

		@Test
		void failingBalanceEventRetriesThreeTimesThenRoutesToWalletEventsDlt() throws Exception {
			UUID eventId = UUID.randomUUID();
			UUID walletId = UUID.randomUUID();
			WalletOpened event = new WalletOpened(walletId, UUID.randomUUID(), Currency.VND);
			doThrow(new IllegalStateException("db down"))
					.when(mockBalanceProjector).project(eq(eventId), any(), anyLong(), any());

			ProducerRecord<String, String> record = new ProducerRecord<>("wallet.events", walletId.toString(),
					eventSerializer.toJson(event));
			record.headers().add("event-id", eventId.toString().getBytes(StandardCharsets.UTF_8));
			record.headers().add("event-type", "WalletOpened".getBytes(StandardCharsets.UTF_8));
			record.headers().add("occurred-at", OCCURRED_AT.toString().getBytes(StandardCharsets.UTF_8));
			record.headers().add("aggregate-version", "1".getBytes(StandardCharsets.UTF_8));

			kafkaTemplate.send(record).get();

			verify(mockBalanceProjector, timeout(5_000).times(4))
					.project(eq(eventId), eq(event), eq(1L), eq(OCCURRED_AT));

			Map<String, Object> props = KafkaTestUtils.consumerProps(embeddedKafkaBroker,
					"dlt-verifier-" + UUID.randomUUID(), false);
			props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
			try (Consumer<String, String> dltConsumer = new DefaultKafkaConsumerFactory<>(
					props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
				embeddedKafkaBroker.consumeFromAnEmbeddedTopic(dltConsumer, "wallet.events.DLT");
				ConsumerRecord<String, String> dltRecord = KafkaTestUtils.getSingleRecord(
						dltConsumer, "wallet.events.DLT", Duration.ofSeconds(5));
				assertThat(dltRecord.key()).isEqualTo(walletId.toString());
				assertThat(new String(dltRecord.headers().lastHeader("event-id").value(), StandardCharsets.UTF_8))
						.isEqualTo(eventId.toString());
			}
		}
	}
}
