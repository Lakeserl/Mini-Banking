package com.lakeserl.mini_banking.shared.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP-layer idempotency backing store (ADR-010).
 * Scoped by {@code (user_id, idempotency_key)} with SHA-256 request hashing, 24-hour TTL,
 * 4-state lifecycle ({@code IN_PROGRESS}, {@code PENDING}, {@code RETRYING}, {@code COMPLETED}),
 * mandatory UUID lease fencing, stale lease reclaim, and periodic expired-key cleanup.
 */
@Service
public class IdempotencyService {

	public static final long DEFAULT_IN_PROGRESS_LEASE_SECONDS = 120L;
	private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

	public sealed interface Decision {
		record Acquired(UUID leaseToken) implements Decision {
			public Acquired {
				Objects.requireNonNull(leaseToken, "leaseToken must not be null");
			}
		}

		record InProgress() implements Decision {
		}

		record HashMismatch() implements Decision {
		}

		record Completed(int status, String body) implements Decision {
		}
	}

	private record StoredResponse(int status, String body) {
	}

	private final JdbcTemplate jdbc;
	private final TransactionTemplate tx;
	private final long inProgressLeaseSeconds;
	private final boolean cleanupEnabled;
	private final JsonMapper mapper = JsonMapper.builder().build();

	@Autowired
	public IdempotencyService(JdbcTemplate jdbc, TransactionTemplate tx,
			@Value("${idempotency.in-progress-lease-seconds:" + DEFAULT_IN_PROGRESS_LEASE_SECONDS + "}") long inProgressLeaseSeconds,
			@Value("${idempotency.cleanup.enabled:true}") boolean cleanupEnabled) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
		this.tx = Objects.requireNonNull(tx, "tx must not be null");
		if (inProgressLeaseSeconds <= 0) {
			throw new IllegalArgumentException(
					"idempotency.in-progress-lease-seconds must be positive, got " + inProgressLeaseSeconds);
		}
		this.inProgressLeaseSeconds = inProgressLeaseSeconds;
		this.cleanupEnabled = cleanupEnabled;
	}

	public IdempotencyService(JdbcTemplate jdbc, TransactionTemplate tx) {
		this(jdbc, tx, DEFAULT_IN_PROGRESS_LEASE_SECONDS, false);
	}

	public Decision begin(UUID userId, String idempotencyKey, String requestHash) {
		Objects.requireNonNull(userId, "userId must not be null");
		Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
		Objects.requireNonNull(requestHash, "requestHash must not be null");

		return tx.execute(status -> {
			jdbc.update("""
					DELETE FROM idempotency_keys
					WHERE user_id = ? AND idempotency_key = ?
					  AND (expires_at <= now()
					       OR (status = 'IN_PROGRESS' AND created_at <= now() - make_interval(secs => ?)))
					""", userId, idempotencyKey, (double) inProgressLeaseSeconds);

			jdbc.update("""
					UPDATE idempotency_keys
					SET status = 'PENDING', lease_token = NULL
					WHERE user_id = ? AND idempotency_key = ?
					  AND status = 'RETRYING'
					  AND expires_at > now()
					  AND created_at <= now() - make_interval(secs => ?)
					""", userId, idempotencyKey, (double) inProgressLeaseSeconds);

			UUID leaseToken = UUID.randomUUID();
			int inserted = jdbc.update("""
					INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash, status, lease_token, created_at, expires_at)
					VALUES (?, ?, ?, 'IN_PROGRESS', ?, now(), now() + interval '24 hours')
					ON CONFLICT (user_id, idempotency_key) DO NOTHING
					""", userId, idempotencyKey, requestHash, leaseToken);
			if (inserted == 1) {
				return new Decision.Acquired(leaseToken);
			}

			int retried = jdbc.update("""
					UPDATE idempotency_keys
					SET status = 'RETRYING', lease_token = ?, created_at = now()
					WHERE user_id = ? AND idempotency_key = ?
					  AND status = 'PENDING' AND request_hash = ? AND expires_at > now()
					""", leaseToken, userId, idempotencyKey, requestHash);
			if (retried == 1) {
				return new Decision.Acquired(leaseToken);
			}

			List<Decision> rows = jdbc.query("""
					SELECT request_hash, status, response_payload::text
					FROM idempotency_keys
					WHERE user_id = ? AND idempotency_key = ?
					""", (rs, rowNum) -> {
				String existingHash = rs.getString("request_hash");
				String rowStatus = rs.getString("status");
				String payload = rs.getString("response_payload");
				if ("IN_PROGRESS".equals(rowStatus) || "RETRYING".equals(rowStatus)) {
					return new Decision.InProgress();
				}
				if (!existingHash.equals(requestHash)) {
					return new Decision.HashMismatch();
				}
				if ("COMPLETED".equals(rowStatus) && payload != null) {
					StoredResponse stored = mapper.readValue(payload, StoredResponse.class);
					return new Decision.Completed(stored.status(), stored.body());
				}
				return new Decision.InProgress();
			}, userId, idempotencyKey);

			return rows.isEmpty() ? new Decision.InProgress() : rows.getFirst();
		});
	}

	public void markPending(UUID userId, String idempotencyKey, UUID leaseToken) {
		Objects.requireNonNull(userId, "userId must not be null");
		Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
		Objects.requireNonNull(leaseToken, "leaseToken must not be null");
		tx.executeWithoutResult(status -> jdbc.update("""
				UPDATE idempotency_keys
				SET status = 'PENDING', lease_token = NULL
				WHERE user_id = ? AND idempotency_key = ?
				  AND status IN ('IN_PROGRESS', 'RETRYING')
				  AND lease_token = ?
				""", userId, idempotencyKey, leaseToken));
	}

	public void complete(UUID userId, String idempotencyKey, UUID leaseToken, int httpStatus, String responseBody) {
		Objects.requireNonNull(userId, "userId must not be null");
		Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
		Objects.requireNonNull(leaseToken, "leaseToken must not be null");
		String json = mapper.writeValueAsString(new StoredResponse(httpStatus, responseBody == null ? "" : responseBody));
		tx.executeWithoutResult(status -> jdbc.update("""
				UPDATE idempotency_keys
				SET status = 'COMPLETED', response_payload = ?::jsonb, lease_token = NULL
				WHERE user_id = ? AND idempotency_key = ?
				  AND status IN ('IN_PROGRESS', 'RETRYING')
				  AND lease_token = ?
				""", json, userId, idempotencyKey, leaseToken));
	}

	public void release(UUID userId, String idempotencyKey, UUID leaseToken) {
		Objects.requireNonNull(userId, "userId must not be null");
		Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
		Objects.requireNonNull(leaseToken, "leaseToken must not be null");
		tx.executeWithoutResult(status -> {
			jdbc.update("""
					DELETE FROM idempotency_keys
					WHERE user_id = ? AND idempotency_key = ?
					  AND status = 'IN_PROGRESS' AND lease_token = ?
					""", userId, idempotencyKey, leaseToken);
			jdbc.update("""
					UPDATE idempotency_keys
					SET status = 'PENDING', lease_token = NULL
					WHERE user_id = ? AND idempotency_key = ?
					  AND status = 'RETRYING' AND lease_token = ?
					""", userId, idempotencyKey, leaseToken);
		});
	}

	@Scheduled(fixedDelayString = "${idempotency.cleanup.interval-ms:3600000}")
	void scheduledCleanup() {
		if (!cleanupEnabled) {
			return;
		}
		try {
			int removed = cleanupExpired();
			if (removed > 0) {
				log.info("Idempotency cleanup removed {} expired keys", removed);
			}
		} catch (RuntimeException ex) {
			log.warn("Scheduled idempotency cleanup failed", ex);
		}
	}

	public int cleanupExpired() {
		return jdbc.update("DELETE FROM idempotency_keys WHERE expires_at <= now()");
	}

	public static String sha256(String method, String path, byte[] body) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(method.getBytes(StandardCharsets.UTF_8));
			digest.update((byte) '|');
			digest.update(path.getBytes(StandardCharsets.UTF_8));
			digest.update((byte) '|');
			if (body != null) {
				digest.update(body);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 not available", e);
		}
	}
}
