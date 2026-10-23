package com.lakeserl.mini_banking.shared.eventsourcing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.lakeserl.mini_banking.shared.domain.DomainEvent;

import tools.jackson.databind.json.JsonMapper;

/** JSON (de)serialisation of event payloads and snapshot states. */
@Component
public class EventSerializer {

	private final JsonMapper mapper = JsonMapper.builder().build();
	private final Map<String, Class<? extends DomainEvent>> types = new HashMap<>();

	public EventSerializer(List<EventTypeProvider> providers) {
		for (EventTypeProvider provider : providers) {
			for (Class<? extends DomainEvent> type : provider.eventTypes()) {
				Class<? extends DomainEvent> previous = types.put(type.getSimpleName(), type);
				if (previous != null && previous != type) {
					throw new IllegalStateException("Duplicate event type name: " + type.getSimpleName());
				}
			}
		}
	}

	public String toJson(Object value) {
		return mapper.writeValueAsString(value);
	}

	public <T> T fromJson(String json, Class<T> type) {
		return mapper.readValue(json, type);
	}

	public DomainEvent deserialize(String eventType, String json) {
		Class<? extends DomainEvent> type = types.get(eventType);
		if (type == null) {
			throw new IllegalStateException("Unknown event type: " + eventType);
		}
		return mapper.readValue(json, type);
	}
}
