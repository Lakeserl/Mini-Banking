package com.lakeserl.mini_banking.shared.eventsourcing;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.postgresql.util.PSQLException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.lakeserl.mini_banking.shared.domain.PendingEvent;

/**
 * Append-only PostgreSQL event store. Events are immutable once written; triggers installed by
 * {@link EventStoreImmutabilityGuard} reject UPDATE, DELETE and TRUNCATE.
 * Optimistic concurrency is enforced by {@code UNIQUE (aggregate_id, version)}.
 */
@Repository
public class PostgresEventStore implements EventStore {

	public static final String UQ_AGGREGATE_VERSION = "uq_events_aggregate_version";
	public static final String PK_EVENT_ID = "events_pkey";
	private static final int SCHEMA_VERSION = 1;

	private static final String INSERT = """
			INSERT INTO events (event_id, aggregate_id, aggregate_type, version, event_type, schema_version,
			                    occurred_at, correlation_id, causation_id, payload)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
			""";

	private static final String SELECT_COLUMNS = """
			event_id, aggregate_id, aggregate_type, version, event_type, schema_version, occurred_at,
			correlation_id, causation_id, payload::text AS payload
			""";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final EventSerializer serializer;
	private final RowMapper<StoredEvent> storedEventRowMapper;

	public PostgresEventStore(JdbcTemplate jdbc, TransactionTemplate tx, EventSerializer serializer) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.serializer = serializer;
		this.storedEventRowMapper = (rs, i) -> new StoredEvent(
				rs.getObject("event_id", UUID.class),
				rs.getObject("aggregate_id", UUID.class),
				rs.getString("aggregate_type"),
				rs.getLong("version"),
				rs.getString("event_type"),
				rs.getInt("schema_version"),
				rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
				rs.getObject("correlation_id", UUID.class),
				rs.getObject("causation_id", UUID.class),
				serializer.deserialize(rs.getString("event_type"), rs.getString("payload")));
	}

	@Override
	public void append(UUID aggregateId, String aggregateType, long expectedVersion, List<PendingEvent> events) {
		Objects.requireNonNull(aggregateId, "aggregateId must not be null");
		Objects.requireNonNull(aggregateType, "aggregateType must not be null");
		Objects.requireNonNull(events, "events must not be null");
		if (aggregateType.isBlank()) {
			throw new IllegalArgumentException("aggregateType must not be blank");
		}
		if (expectedVersion < 0) {
			throw new IllegalArgumentException("expectedVersion must not be negative");
		}
		if (events.isEmpty()) {
			return;
		}
		if (events.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("events must not contain null values");
		}
		OffsetDateTime now = OffsetDateTime.now();
		tx.executeWithoutResult(status -> {
			long actualVersion = latestVersion(aggregateId);
			if (actualVersion != expectedVersion) {
				throw new OptimisticConcurrencyException(
						"Aggregate " + aggregateId + " is at version " + actualVersion + " but expected " + expectedVersion);
			}
			long version = expectedVersion;
			for (PendingEvent pending : events) {
				version++;
				insert(aggregateId, aggregateType, version, pending, now);
			}
		});
	}

	private void insert(UUID aggregateId, String aggregateType, long version, PendingEvent pending,
			OffsetDateTime occurredAt) {
		try {
			jdbc.update(INSERT, pending.eventId(), aggregateId, aggregateType, version,
					pending.event().eventType(), SCHEMA_VERSION, occurredAt, pending.correlationId(),
					pending.causationId(), serializer.toJson(pending.event()));
		} catch (DuplicateKeyException e) {
			handleDuplicateKeyException(e, aggregateId, version, pending.eventId());
		}
	}

	private void handleDuplicateKeyException(DuplicateKeyException e, UUID aggregateId, long version, UUID eventId) {
		Throwable cause = e.getCause();
		while (cause != null) {
			if (cause instanceof PSQLException psqlEx) {
				String constraint = psqlEx.getServerErrorMessage() != null
						? psqlEx.getServerErrorMessage().getConstraint()
						: null;
				if (constraint != null) {
					if (constraint.equalsIgnoreCase(UQ_AGGREGATE_VERSION)) {
						throw new OptimisticConcurrencyException(
								"Version " + version + " of aggregate " + aggregateId + " already exists", e);
					}
					if (constraint.equalsIgnoreCase(PK_EVENT_ID) || constraint.equalsIgnoreCase("events_pkey")) {
						throw new DuplicateEventException(eventId, e);
					}
				}
			}
			cause = cause.getCause();
		}
		// Fallback to error message containment check for portability
		String message = String.valueOf(e.getMessage());
		if (message.contains(UQ_AGGREGATE_VERSION)) {
			throw new OptimisticConcurrencyException(
					"Version " + version + " of aggregate " + aggregateId + " already exists", e);
		}
		if (message.contains(PK_EVENT_ID) || message.contains("events_pkey")) {
			throw new DuplicateEventException(eventId, e);
		}
		throw e;
	}

	@Override
	public List<StoredEvent> load(UUID aggregateId, long afterVersion) {
		return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM events WHERE aggregate_id = ? AND version > ? ORDER BY version",
				storedEventRowMapper, aggregateId, afterVersion);
	}

	@Override
	public boolean contains(UUID eventId) {
		Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM events WHERE event_id = ?)",
				Boolean.class, eventId);
		return Boolean.TRUE.equals(exists);
	}

	@Override
	public Optional<StoredEvent> findEvent(UUID eventId) {
		List<StoredEvent> results = jdbc.query("SELECT " + SELECT_COLUMNS + " FROM events WHERE event_id = ?",
				storedEventRowMapper, eventId);
		return results.stream().findFirst();
	}

	@Override
	public long latestVersion(UUID aggregateId) {
		Long max = jdbc.queryForObject(
				"SELECT COALESCE(MAX(version), 0) FROM events WHERE aggregate_id = ?",
				Long.class, aggregateId);
		return max != null ? max : 0L;
	}
}
