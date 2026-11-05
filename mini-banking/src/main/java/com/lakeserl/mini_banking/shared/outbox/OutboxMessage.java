package com.lakeserl.mini_banking.shared.outbox;

import java.util.UUID;

/** One pending row of the {@code outbox} table, as handed to an {@link OutboxPublisher}. */
public record OutboxMessage(long id, UUID eventId, UUID aggregateId, String aggregateType, String eventType,
		String payload) {
}
