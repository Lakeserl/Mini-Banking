package com.lakeserl.mini_banking.shared.outbox;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Single-replica outbox relay (ADR-004): publishes unpublished rows in id order and stops at the first failure
 * so per-aggregate ordering is never broken. Delivery is at-least-once.
 */
@Component
@ConditionalOnProperty(name = "outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	private static final String SELECT_PENDING = """
			SELECT id, event_id, aggregate_id, aggregate_type, event_type, payload::text AS payload
			FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED
			""";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final OutboxPublisher publisher;
	private final int batchSize;

	public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, OutboxPublisher publisher,
			@Value("${outbox.relay.batch-size:100}") int batchSize) {
		if (batchSize <= 0) {
			throw new IllegalArgumentException("outbox.relay.batch-size must be positive");
		}
		this.jdbc = jdbc;
		this.tx = tx;
		this.publisher = publisher;
		this.batchSize = batchSize;
	}

	@Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:200}")
	void poll() {
		try {
			relayOnce();
		} catch (RuntimeException e) {
			log.warn("Outbox relay poll failed; will retry", e);
		}
	}

	/** Publishes one batch; returns the number of rows published and marked. */
	int relayOnce() {
		AtomicInteger published = new AtomicInteger();
		tx.executeWithoutResult(status -> {
			List<OutboxMessage> pending = jdbc.query(SELECT_PENDING, (rs, i) -> new OutboxMessage(rs.getLong("id"),
					rs.getObject("event_id", UUID.class), rs.getObject("aggregate_id", UUID.class),
					rs.getString("aggregate_type"), rs.getString("event_type"), rs.getString("payload")), batchSize);
			for (OutboxMessage message : pending) {
				try {
					publisher.publish(message);
				} catch (OutboxPublishException e) {
					log.warn("Outbox message {} not published; stopping batch", message.id(), e);
					return;
				}
				jdbc.update("UPDATE outbox SET published_at = now() WHERE id = ?", message.id());
				published.incrementAndGet();
			}
		});
		return published.get();
	}
}
