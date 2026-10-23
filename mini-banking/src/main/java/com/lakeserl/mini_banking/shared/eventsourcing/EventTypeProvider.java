package com.lakeserl.mini_banking.shared.eventsourcing;

import java.util.List;

import com.lakeserl.mini_banking.shared.domain.DomainEvent;

/** Implemented by each module to tell the serializer which event classes it owns. */
public interface EventTypeProvider {

	List<Class<? extends DomainEvent>> eventTypes();
}
