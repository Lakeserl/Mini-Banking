package com.lakeserl.mini_banking.shared.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP-layer idempotency backing store (ADR-010).
 * Scoped by {@code (user_id, idempotency_key)} with SHA-256 request hashing and 24-hour TTL.
 */
@Service
public class IdempotencyService {

	public sealed interface Decision {
		record Acquired() implements Decision {
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
	private final JsonMapper mapper = JsonMapper.builder().build();

	public IdempotencyService(JdbcTemplate jdbc, TransactionTemplate tx) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
		this.tx = Objects.requireNonNull(tx, "tx must not be null");
	}

	public Decision begin(UUID userId, String idempotencyKey, String requestHash) {
		Objects.requireNonNull(userId, "userId must not be null");
		Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
		Objects.requireNonNull(requestHash, "requestHash must not be null");

		return tx.execute(status -> {
			jdbc.update("""
					DELETE FROM idempotency_keys
					WHERE user_id = ? AND idempotency_key = ? AND expires_at <= now()
					""", userId, idempotencyKey);

			int inserted = jdbc.update("""
					INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash, status, created_at, expires_at)
					VALUES (?, ?, ?, 'IN_PROGRESS', now(), now() + interval '24 hours')
					ON CONFLICT (user_id, idempotency_key) DO NOTHING
					""", userId, idempotencyKey, requestHash);

			if (inserted == 1) {
				return new Decision.Acquired();
			}

			return jdbc.queryForObject("""
					SELECT request_hash, status, response_payload::text
					FROM idempotency_keys
					WHERE user_id = ? AND idempotency_key = ?
					""", (rs, rowNum) -> {
				String existingHash = rs.getString("request_hash");
				String rowStatus = rs.getString("status");
				String payload = rs.getString("response_payload");
				if ("IN_PROGRESS".equals(rowStatus)) {
					return new Decision.InProgress();
				}
				if (!existingHash.equals(requestHash)) {
					return new Decision.HashMismatch();
				}
				StoredResponse stored = mapper.readValue(payload, StoredResponse.class);
				return new Decision.Completed(stored.status(), stored.body());
			}, userId, idempotencyKey);
		});
	}

	public void complete(UUID userId, String idempotencyKey, int httpStatus, String responseBody) {
		Objects.requireNonNull(userId, "userId must not be null");
		Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
		String json = mapper.writeValueAsString(new StoredResponse(httpStatus, responseBody == null ? "" : responseBody));
		tx.executeWithoutResult(status -> jdbc.update("""
				UPDATE idempotency_keys
				SET status = 'COMPLETED', response_payload = ?::jsonb
				WHERE user_id = ? AND idempotency_key = ? AND status = 'IN_PROGRESS'
				""", json, userId, idempotencyKey));
	}

	public void release(UUID userId, String idempotencyKey) {
		Objects.requireNonNull(userId, "userId must not be null");
		Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
		tx.executeWithoutResult(status -> jdbc.update("""
				DELETE FROM idempotency_keys
				WHERE user_id = ? AND idempotency_key = ? AND status = 'IN_PROGRESS'
				""", userId, idempotencyKey));
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
