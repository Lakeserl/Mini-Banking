package com.lakeserl.mini_banking.shared.eventsourcing;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Schema mapping for Hibernate validation. Flyway owns table and trigger creation; rows are never read
 * or written through JPA (see {@link PostgresEventStore}, ADR-008).
 * The constraint names are relied on by the store to tell version conflicts from duplicate event ids.
 */
@Entity
@Table(name = "events", uniqueConstraints = @UniqueConstraint(name = PostgresEventStore.UQ_AGGREGATE_VERSION,
		columnNames = { "aggregate_id", "version" }))
@Check(constraints = "version >= 1")
@SuppressWarnings("unused")
public class EventEntity {

	@Id
	@Column(name = "event_id")
	private UUID eventId;

	@Column(name = "aggregate_id", nullable = false)
	private UUID aggregateId;

	@Column(name = "aggregate_type", nullable = false, length = 50)
	private String aggregateType;

	@Column(name = "version", nullable = false)
	private long version;

	@Column(name = "event_type", nullable = false, length = 100)
	private String eventType;

	@Column(name = "schema_version", nullable = false)
	private int schemaVersion;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Column(name = "correlation_id", nullable = false)
	private UUID correlationId;

	@Column(name = "causation_id")
	private UUID causationId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload", nullable = false, columnDefinition = "jsonb")
	private String payload;

	protected EventEntity() {
	}
}
