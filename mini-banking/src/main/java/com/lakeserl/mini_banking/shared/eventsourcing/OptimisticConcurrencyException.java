package com.lakeserl.mini_banking.shared.eventsourcing;

/** Another writer appended to the aggregate first (version conflict). Reload and retry. */
public class OptimisticConcurrencyException extends RuntimeException {

	public OptimisticConcurrencyException(String message, Throwable cause) {
		super(message, cause);
	}

	public OptimisticConcurrencyException(String message) {
		super(message);
	}
}
