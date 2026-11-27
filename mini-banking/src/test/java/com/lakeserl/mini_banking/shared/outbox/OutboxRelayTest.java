package com.lakeserl.mini_banking.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.lakeserl.mini_banking.PostgresDataTest;

@PostgresDataTest
class OutboxRelayTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate tx;

	@BeforeEach
	void clean() {
		jdbc.update("DELETE FROM outbox");
	}

	/** Records published messages and ids; fails the first attempt for {@code failOnId} when set. */
	static class FakePublisher implements OutboxPublisher {
		final List<Long> published = new ArrayList<>();
		final List<OutboxMessage> messages = new ArrayList<>();
		Long failOnceOnId;

		@Override
		public void publish(OutboxMessage message) {
			if (failOnceOnId != null && failOnceOnId == message.id()) {
				failOnceOnId = null;
				throw new OutboxPublishException("boom", null);
			}
			published.add(message.id());
			messages.add(message);
		}
	}

	private List<Long> insertRows(int count) {
		UUID aggregateId = UUID.randomUUID();
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			ids.add(jdbc.queryForObject("""
					INSERT INTO outbox (event_id, aggregate_id, aggregate_type, event_type, payload, created_at, aggregate_version)
					VALUES (?, ?, 'Wallet', 'WalletCredited', '{}'::jsonb, now(), ?) RETURNING id
					""", Long.class, UUID.randomUUID(), aggregateId, (long) (i + 1)));
		}
		return ids;
	}

	private int unpublishedCount() {
		return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Integer.class);
	}

	private List<Instant> persistedCreatedAtInOrder() {
		return jdbc.query("SELECT created_at FROM outbox ORDER BY id",
				(rs, i) -> rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}

	@Test
	void relayOnce_publishesInIdOrderAndMarksPublished() {
		List<Long> ids = insertRows(3);
		List<Instant> expectedOccurredAt = persistedCreatedAtInOrder();
		FakePublisher publisher = new FakePublisher();
		OutboxRelay relay = new OutboxRelay(jdbc, tx, publisher, 100);

		assertThat(relay.relayOnce()).isEqualTo(3);

		assertThat(publisher.published).containsExactlyElementsOf(ids);
		assertThat(publisher.messages)
				.extracting(OutboxMessage::occurredAt)
				.doesNotContainNull()
				.containsExactlyElementsOf(expectedOccurredAt);
		assertThat(unpublishedCount()).isZero();
	}

	@Test
	void relayOnce_stopsAtFirstFailureAndRetriesSameRowNext() {
		List<Long> ids = insertRows(3);
		List<Instant> expectedOccurredAt = persistedCreatedAtInOrder();
		FakePublisher publisher = new FakePublisher();
		publisher.failOnceOnId = ids.get(1);
		OutboxRelay relay = new OutboxRelay(jdbc, tx, publisher, 100);

		assertThat(relay.relayOnce()).isEqualTo(1);
		assertThat(publisher.published).containsExactly(ids.get(0));
		assertThat(unpublishedCount()).isEqualTo(2);

		assertThat(relay.relayOnce()).isEqualTo(2);
		assertThat(publisher.published).containsExactlyElementsOf(ids);
		assertThat(publisher.messages)
				.extracting(OutboxMessage::occurredAt)
				.doesNotContainNull()
				.containsExactlyElementsOf(expectedOccurredAt);
		assertThat(unpublishedCount()).isZero();
	}

	@Test
	void relayOnce_respectsBatchSize() {
		insertRows(5);
		OutboxRelay relay = new OutboxRelay(jdbc, tx, new FakePublisher(), 2);

		assertThat(relay.relayOnce()).isEqualTo(2);
		assertThat(unpublishedCount()).isEqualTo(3);
	}

	@Test
	void relayOnce_returnsZeroWhenNothingPending() {
		OutboxRelay relay = new OutboxRelay(jdbc, tx, new FakePublisher(), 100);

		assertThat(relay.relayOnce()).isZero();
	}

	@Test
	@SuppressWarnings("unchecked")
	void relayCommitsEarlierRowsWhenLaterRowThrowsSynchronousKafkaException() {
		List<Long> ids = insertRows(2);
		KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
		when(kafkaTemplate.send(any(ProducerRecord.class)))
				.thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)))
				.thenThrow(new KafkaException("metadata timeout"));
		OutboxRelay relay = new OutboxRelay(jdbc, tx, new KafkaOutboxPublisher(kafkaTemplate), 100);

		assertThat(relay.relayOnce()).isEqualTo(1);
		OffsetDateTime firstPublishedAt = jdbc.queryForObject(
				"SELECT published_at FROM outbox WHERE id = ?", OffsetDateTime.class, ids.get(0));
		OffsetDateTime secondPublishedAt = jdbc.queryForObject(
				"SELECT published_at FROM outbox WHERE id = ?", OffsetDateTime.class, ids.get(1));
		assertThat(firstPublishedAt).isNotNull();
		assertThat(secondPublishedAt).isNull();
	}
}
