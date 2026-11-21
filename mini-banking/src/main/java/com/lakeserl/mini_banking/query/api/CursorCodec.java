package com.lakeserl.mini_banking.query.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;

/**
 * Encodes and decodes opaque Base64URL keyset cursors {@code (occurredAt, id)} for cursor-based pagination.
 */
public final class CursorCodec {

	public record Cursor(Instant occurredAt, long id) {
		public Cursor {
			Objects.requireNonNull(occurredAt, "occurredAt must not be null");
			if (id <= 0) {
				throw new IllegalArgumentException("cursor id must be positive");
			}
		}
	}

	private CursorCodec() {
	}

	public static String encode(Instant occurredAt, long id) {
		Objects.requireNonNull(occurredAt, "occurredAt must not be null");
		if (id <= 0) {
			throw new IllegalArgumentException("cursor id must be positive");
		}
		String raw = occurredAt + "|" + id;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	public static Cursor decode(String cursor) {
		if (cursor == null || cursor.isBlank()) {
			throw new IllegalArgumentException("cursor must not be blank");
		}
		String raw;
		try {
			raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Malformed cursor encoding", e);
		}
		int sep = raw.indexOf('|');
		if (sep <= 0 || sep == raw.length() - 1 || raw.indexOf('|', sep + 1) >= 0) {
			throw new IllegalArgumentException("Malformed cursor payload");
		}
		try {
			Instant occurredAt = Instant.parse(raw.substring(0, sep));
			long id = Long.parseLong(raw.substring(sep + 1));
			return new Cursor(occurredAt, id);
		} catch (DateTimeParseException | NumberFormatException e) {
			throw new IllegalArgumentException("Malformed cursor values", e);
		}
	}
}
