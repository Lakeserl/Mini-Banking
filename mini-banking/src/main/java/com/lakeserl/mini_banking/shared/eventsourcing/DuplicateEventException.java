package com.lakeserl.mini_banking.shared.eventsourcing;

import java.util.UUID;

/** An event with this id is already stored, i.e. the operation was already applied (a retry). */
public class DuplicateEventException extends RuntimeException {

	public DuplicateEventException(UUID eventId, Throwable cause) {
		super("Event already stored: " + eventId, cause);
	}
}
