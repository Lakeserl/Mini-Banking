package com.lakeserl.mini_banking.shared.eventsourcing;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Schema definition of the {@code snapshots} table (one row per aggregate, latest snapshot only).
 * {@code snapshot_schema_version} tells whether the stored state can still be read by the current code.
 * Accessed through JDBC by {@link PostgresSnapshotStore}.
 */
@Entity
@Table(name = "snapshots")
@SuppressWarnings("unused")
public class SnapshotEntity {

	@Id
	@Column(name = "aggregate_id")
	private UUID aggregateId;

	@Column(name = "aggregate_type", nullable = false, length = 50)
	private String aggregateType;

	@Column(name = "version", nullable = false)
	private long version;

	@Column(name = "snapshot_schema_version", nullable = false)
	private int snapshotSchemaVersion;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "state", nullable = false, columnDefinition = "jsonb")
	private String state;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected SnapshotEntity() {
	}
}
