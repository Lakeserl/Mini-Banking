package com.lakeserl.mini_banking.shared.eventsourcing;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Stores the latest snapshot per aggregate. Snapshots are an optimisation; events remain the truth. */
@Repository
public class PostgresSnapshotStore {

	public record Snapshot(UUID aggregateId, String aggregateType, long version, int schemaVersion, String stateJson) {
	}

	private final JdbcTemplate jdbc;

	public PostgresSnapshotStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Keeps only the newest snapshot (older versions never overwrite a newer one). */
	public void save(UUID aggregateId, String aggregateType, long version, int schemaVersion, String stateJson) {
		jdbc.update("""
				INSERT INTO snapshots (aggregate_id, aggregate_type, version, snapshot_schema_version, state, created_at)
				VALUES (?, ?, ?, ?, ?::jsonb, now())
				ON CONFLICT (aggregate_id) DO UPDATE
				SET version = EXCLUDED.version,
				    snapshot_schema_version = EXCLUDED.snapshot_schema_version,
				    state = EXCLUDED.state,
				    created_at = EXCLUDED.created_at
				WHERE snapshots.version < EXCLUDED.version
				""", aggregateId, aggregateType, version, schemaVersion, stateJson);
	}

	/**
	 * Returns the snapshot only if it was written with {@code schemaVersion}.
	 * Read-only operation without side-effects on the hot path.
	 */
	public Optional<Snapshot> find(UUID aggregateId, int schemaVersion) {
		return jdbc.query("""
				SELECT aggregate_id, aggregate_type, version, snapshot_schema_version, state::text AS state
				FROM snapshots
				WHERE aggregate_id = ? AND snapshot_schema_version = ?
				""", (rs, i) -> new Snapshot(
				rs.getObject("aggregate_id", UUID.class),
				rs.getString("aggregate_type"),
				rs.getLong("version"),
				rs.getInt("snapshot_schema_version"),
				rs.getString("state")), aggregateId, schemaVersion)
				.stream().findFirst();
	}

	/** Explicit maintenance cleanup for obsolete snapshot schemas. */
	public int deleteObsoleteSnapshots(UUID aggregateId, int currentSchemaVersion) {
		return jdbc.update("DELETE FROM snapshots WHERE aggregate_id = ? AND snapshot_schema_version <> ?",
				aggregateId, currentSchemaVersion);
	}
}
