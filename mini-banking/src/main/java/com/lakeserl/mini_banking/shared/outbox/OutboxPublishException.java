package com.lakeserl.mini_banking.shared.outbox;

public class OutboxPublishException extends RuntimeException {

	public OutboxPublishException(String message, Throwable cause) {
		super(message, cause);
	}
}
