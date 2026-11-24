package com.lakeserl.mini_banking.reconciliation;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.lakeserl.mini_banking.query.api.CursorCodec.Cursor;

/**
 * Persistence store for {@code reconciliation_issues} with idempotent open-issue deduplication
 * via partial unique index {@code uq_recon_open_dedupe} and audited manual resolution.
 */
@Repository
public class ReconciliationIssueRepository {

	public record ReconciliationIssue(
			long id,
			ReconciliationIssueType issueType,
			String dedupeKey,
			String detailsJson,
			ReconciliationIssueStatus status,
			Instant detectedAt,
			Instant resolvedAt,
			String resolvedBy,
			String resolutionNotes) {
	}

	public static class ReconciliationIssueNotFoundException extends RuntimeException {
		public ReconciliationIssueNotFoundException(long id) {
			super("Reconciliation issue not found: " + id);
		}
	}

	public static class ReconciliationIssueAlreadyResolvedException extends RuntimeException {
		public ReconciliationIssueAlreadyResolvedException(long id) {
			super("Reconciliation issue is already resolved: " + id);
		}
	}

	private static final String INSERT_OPEN_SQL = """
			INSERT INTO reconciliation_issues (issue_type, dedupe_key, details, status, detected_at)
			VALUES (?, ?, ?::jsonb, 'OPEN', ?)
			ON CONFLICT (dedupe_key) WHERE status = 'OPEN' DO NOTHING
			""";

	private static final String SELECT_COLUMNS = """
			id, issue_type, dedupe_key, details::text AS details, status, detected_at,
			resolved_at, resolved_by, resolution_notes
			""";

	private static final String SELECT_BY_STATUS_FIRST_SQL = "SELECT " + SELECT_COLUMNS + """
			FROM reconciliation_issues
			WHERE status = ?
			ORDER BY detected_at DESC, id DESC
			LIMIT ?
			""";

	private static final String SELECT_BY_STATUS_CURSOR_SQL = "SELECT " + SELECT_COLUMNS + """
			FROM reconciliation_issues
			WHERE status = ?
			  AND (detected_at, id) < (?, ?)
			ORDER BY detected_at DESC, id DESC
			LIMIT ?
			""";

	private static final String RESOLVE_SQL = """
			UPDATE reconciliation_issues
			SET status = 'RESOLVED', resolved_at = ?, resolved_by = ?, resolution_notes = ?
			WHERE id = ? AND status = 'OPEN'
			""" + "RETURNING " + SELECT_COLUMNS;

	private static final String SELECT_STATUS_SQL = "SELECT status FROM reconciliation_issues WHERE id = ?";

	private static final RowMapper<ReconciliationIssue> MAPPER = (rs, i) -> {
		OffsetDateTime resolvedTs = rs.getObject("resolved_at", OffsetDateTime.class);
		return new ReconciliationIssue(
				rs.getLong("id"),
				ReconciliationIssueType.valueOf(rs.getString("issue_type")),
				rs.getString("dedupe_key"),
				rs.getString("details"),
				ReconciliationIssueStatus.valueOf(rs.getString("status")),
				rs.getObject("detected_at", OffsetDateTime.class).toInstant(),
				resolvedTs != null ? resolvedTs.toInstant() : null,
				rs.getString("resolved_by"),
				rs.getString("resolution_notes"));
	};

	private final JdbcTemplate jdbc;

	public ReconciliationIssueRepository(JdbcTemplate jdbc) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
	}

	/**
	 * Opens a new {@code OPEN} reconciliation issue if no {@code OPEN} issue with the same {@code dedupeKey} exists.
	 *
	 * @return {@code true} if a new issue was created, {@code false} if an identical open issue already existed.
	 */
	public boolean openIfAbsent(ReconciliationIssueType issueType, String dedupeKey, String detailsJson,
			Instant detectedAt) {
		Objects.requireNonNull(issueType, "issueType");
		Objects.requireNonNull(dedupeKey, "dedupeKey");
		Objects.requireNonNull(detailsJson, "detailsJson");
		Objects.requireNonNull(detectedAt, "detectedAt");
		if (dedupeKey.isBlank() || dedupeKey.length() > 128) {
			throw new IllegalArgumentException("dedupeKey must be 1..128 non-blank characters");
		}
		int inserted = jdbc.update(INSERT_OPEN_SQL, issueType.name(), dedupeKey, detailsJson,
				OffsetDateTime.ofInstant(detectedAt, ZoneOffset.UTC));
		return inserted == 1;
	}

	public List<ReconciliationIssue> findByStatus(ReconciliationIssueStatus status, Cursor cursor, int fetchSize) {
		Objects.requireNonNull(status, "status");
		if (fetchSize <= 0) {
			throw new IllegalArgumentException("fetchSize must be positive");
		}
		if (cursor == null) {
			return jdbc.query(SELECT_BY_STATUS_FIRST_SQL, MAPPER, status.name(), fetchSize);
		}
		OffsetDateTime ts = OffsetDateTime.ofInstant(cursor.occurredAt(), ZoneOffset.UTC);
		return jdbc.query(SELECT_BY_STATUS_CURSOR_SQL, MAPPER, status.name(), ts, cursor.id(), fetchSize);
	}

	public ReconciliationIssue resolve(long id, String resolvedBy, String resolutionNotes, Instant resolvedAt) {
		Objects.requireNonNull(resolvedBy, "resolvedBy");
		Objects.requireNonNull(resolutionNotes, "resolutionNotes");
		Objects.requireNonNull(resolvedAt, "resolvedAt");
		if (resolvedBy.isBlank()) {
			throw new IllegalArgumentException("resolvedBy must not be blank");
		}
		if (resolutionNotes.isBlank()) {
			throw new IllegalArgumentException("resolutionNotes must not be blank");
		}
		List<ReconciliationIssue> updated = jdbc.query(RESOLVE_SQL, MAPPER,
				OffsetDateTime.ofInstant(resolvedAt, ZoneOffset.UTC), resolvedBy, resolutionNotes, id);
		if (!updated.isEmpty()) {
			return updated.getFirst();
		}
		List<String> existing = jdbc.query(SELECT_STATUS_SQL, (rs, i) -> rs.getString("status"), id);
		if (existing.isEmpty()) {
			throw new ReconciliationIssueNotFoundException(id);
		}
		throw new ReconciliationIssueAlreadyResolvedException(id);
	}
}
