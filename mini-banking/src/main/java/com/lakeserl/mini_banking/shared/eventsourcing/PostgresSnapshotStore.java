package com.lakeserl.mini_banking.shared.eventsourcing;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Stores the latest snapshot per aggregate. Snapshots are an optimisation; events remain the truth. */
@Repository
public class PostgresSnapshotStore {

	public record Snapshot(UUID aggregateId, String aggregateType, long version, UUID lastEventId,
			int snapshotSchemaVersion, String stateHash, String stateJson) {
	}

	private final JdbcTemplate jdbc;

	public PostgresSnapshotStore(JdbcTemplate jdbc) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
	}

	/** Keeps only the newest snapshot (older versions never overwrite a newer one). */
	public void save(UUID aggregateId, String aggregateType, long version, UUID lastEventId, int schemaVersion,
			String stateHash, String stateJson) {
		Objects.requireNonNull(aggregateId, "aggregateId must not be null");
		Objects.requireNonNull(aggregateType, "aggregateType must not be null");
		Objects.requireNonNull(lastEventId, "lastEventId must not be null");
		Objects.requireNonNull(stateHash, "stateHash must not be null");
		Objects.requireNonNull(stateJson, "stateJson must not be null");
		if (version < 1 || schemaVersion < 1 || stateHash.length() != 64) {
			throw new IllegalArgumentException("Invalid snapshot version, schema version, or state hash");
		}
		jdbc.update("""
				INSERT INTO snapshots (aggregate_id, aggregate_type, version, last_event_id, snapshot_schema_version, state_hash, state, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, now())
				ON CONFLICT (aggregate_id) DO UPDATE
				SET version = EXCLUDED.version,
				    last_event_id = EXCLUDED.last_event_id,
				    snapshot_schema_version = EXCLUDED.snapshot_schema_version,
				    state_hash = EXCLUDED.state_hash,
				    state = EXCLUDED.state,
				    created_at = EXCLUDED.created_at
				WHERE snapshots.version < EXCLUDED.version
				""", aggregateId, aggregateType, version, lastEventId, schemaVersion, stateHash, stateJson);
	}

	/**
	 * Returns the snapshot only if it was written with {@code schemaVersion}.
	 * Read-only operation without side-effects on the hot path.
	 */
	public Optional<Snapshot> find(UUID aggregateId, int schemaVersion) {
		Objects.requireNonNull(aggregateId, "aggregateId must not be null");
		return jdbc.query("""
				SELECT aggregate_id, aggregate_type, version, last_event_id, snapshot_schema_version, state_hash,
				       state::text AS state
				FROM snapshots
				WHERE aggregate_id = ? AND snapshot_schema_version = ?
				""", (rs, i) -> new Snapshot(
				rs.getObject("aggregate_id", UUID.class),
				rs.getString("aggregate_type"),
				rs.getLong("version"),
				rs.getObject("last_event_id", UUID.class),
				rs.getInt("snapshot_schema_version"),
				rs.getString("state_hash"),
				rs.getString("state")), aggregateId, schemaVersion)
				.stream().findFirst();
	}

	/** Explicit maintenance cleanup for obsolete snapshot schemas. */
	public int deleteObsoleteSnapshots(UUID aggregateId, int currentSchemaVersion) {
		Objects.requireNonNull(aggregateId, "aggregateId must not be null");
		return jdbc.update("DELETE FROM snapshots WHERE aggregate_id = ? AND snapshot_schema_version <> ?",
				aggregateId, currentSchemaVersion);
	}
}
