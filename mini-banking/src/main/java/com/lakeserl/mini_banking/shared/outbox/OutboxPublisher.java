package com.lakeserl.mini_banking.shared.outbox;

public interface OutboxPublisher {

	/** Publishes one message; returns only once the broker has acknowledged it. */
	void publish(OutboxMessage message) throws OutboxPublishException;
}
