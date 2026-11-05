package com.lakeserl.mini_banking.shared.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Database-backed idempotency state machine scoped by {@code (user_id, idempotency_key)} with a 24-hour TTL (ADR-010).
 */
@Service
public class IdempotencyService {

	public static final Duration TTL = Duration.ofHours(24);
	private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
	private static final String STATUS_COMPLETED = "COMPLETED";

	private static final String DELETE_EXPIRED = """
			DELETE FROM idempotency_keys
			WHERE user_id = ? AND idempotency_key = ? AND expires_at <= ?
			""";

	private static final String INSERT_IF_ABSENT = """
			INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash, status, created_at, expires_at)
			VALUES (?, ?, ?, 'IN_PROGRESS', ?, ?)
			ON CONFLICT (user_id, idempotency_key) DO NOTHING
			""";

	private static final String SELECT_EXISTING = """
			SELECT request_hash, status, response_payload::text AS response_payload
			FROM idempotency_keys
			WHERE user_id = ? AND idempotency_key = ?
			""";

	private static final String COMPLETE_SQL = """
			UPDATE idempotency_keys
			SET status = 'COMPLETED', response_payload = ?::jsonb
			WHERE user_id = ? AND idempotency_key = ?
			""";

	private final JdbcTemplate jdbc;
	private final Clock clock;

	public IdempotencyService(JdbcTemplate jdbc, Clock clock) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	public IdempotencyResult begin(UUID userId, String idempotencyKey, String requestHash) {
		Objects.requireNonNull(userId, "userId must not be null");
		Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
		Objects.requireNonNull(requestHash, "requestHash must not be null");
		if (idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
			throw new IllegalArgumentException("idempotencyKey must be 1..100 non-blank characters");
		}
		if (requestHash.length() != 64) {
			throw new IllegalArgumentException("requestHash must be a 64-char SHA-256 hex digest");
		}

		OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
		OffsetDateTime expiresAt = now.plus(TTL);

		jdbc.update(DELETE_EXPIRED, userId, idempotencyKey, now);

		int inserted = jdbc.update(INSERT_IF_ABSENT, userId, idempotencyKey, requestHash, now, expiresAt);
		if (inserted == 1) {
			return new IdempotencyResult.New();
		}

		List<ExistingRow> rows = jdbc.query(SELECT_EXISTING,
				(rs, i) -> new ExistingRow(rs.getString("request_hash").trim(), rs.getString("status"),
						rs.getString("response_payload")),
				userId, idempotencyKey);

		if (rows.isEmpty()) {
			return new IdempotencyResult.InProgress();
		}
		ExistingRow existing = rows.get(0);
		if (STATUS_IN_PROGRESS.equals(existing.status())) {
			return new IdempotencyResult.InProgress();
		}
		if (STATUS_COMPLETED.equals(existing.status())) {
			if (existing.requestHash().equals(requestHash)) {
				return new IdempotencyResult.Replay(existing.responsePayload());
			}
			return new IdempotencyResult.HashMismatch();
		}
		throw new IllegalStateException("Unexpected idempotency status: " + existing.status());
	}

	public void complete(UUID userId, String idempotencyKey, String responsePayload) {
		Objects.requireNonNull(userId, "userId must not be null");
		Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
		Objects.requireNonNull(responsePayload, "responsePayload must not be null");
		int updated = jdbc.update(COMPLETE_SQL, responsePayload, userId, idempotencyKey);
		if (updated == 0) {
			throw new IllegalStateException(
					"No idempotency record found to complete for user " + userId + " and key " + idempotencyKey);
		}
	}

	public static String hash(String requestBody) {
		Objects.requireNonNull(requestBody, "requestBody must not be null");
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest(requestBody.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(bytes);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}

	private record ExistingRow(String requestHash, String status, String responsePayload) {
	}
}
