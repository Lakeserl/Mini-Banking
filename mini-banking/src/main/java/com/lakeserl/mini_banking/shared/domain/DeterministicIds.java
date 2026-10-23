package com.lakeserl.mini_banking.shared.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * Tiện ích sinh UUID xác định (deterministic UUID) chuẩn RFC 4122 (UUID Version 5 - SHA-1).
 */
public final class DeterministicIds {

	private static final ThreadLocal<MessageDigest> SHA1_HOLDER = ThreadLocal.withInitial(() -> {
		try {
			return MessageDigest.getInstance("SHA-1");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-1 algorithm not available in current JVM", e);
		}
	});

	private DeterministicIds() {
	}

	/**
	 * Sinh UUID v5 xác định theo chuẩn RFC 4122 dựa trên namespace và chuỗi name.
	 *
	 * @param namespace UUID namespace
	 * @param name      Chuỗi định danh (không được null)
	 * @return UUID version 5 tuân thủ RFC 4122
	 */
	public static UUID v5(UUID namespace, String name) {
		Objects.requireNonNull(namespace, "namespace must not be null");
		Objects.requireNonNull(name, "name must not be null");
		if (name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}

		MessageDigest md = SHA1_HOLDER.get();
		md.reset();

		long msb = namespace.getMostSignificantBits();
		long lsb = namespace.getLeastSignificantBits();
		byte[] nsBytes = new byte[16];
		writeLong(msb, nsBytes, 0);
		writeLong(lsb, nsBytes, 8);
		md.update(nsBytes);

		byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
		byte[] hash = md.digest(nameBytes);

		// RFC 4122:
		// Version 5: 0x50 in octet 6
		hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
		// Variant: 0x80 in octet 8 (RFC 4122 / Leach-Salz)
		hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);

		long finalMsb = readLong(hash, 0);
		long finalLsb = readLong(hash, 8);

		return new UUID(finalMsb, finalLsb);
	}

	private static void writeLong(long value, byte[] dest, int offset) {
		dest[offset]     = (byte) (value >>> 56);
		dest[offset + 1] = (byte) (value >>> 48);
		dest[offset + 2] = (byte) (value >>> 40);
		dest[offset + 3] = (byte) (value >>> 32);
		dest[offset + 4] = (byte) (value >>> 24);
		dest[offset + 5] = (byte) (value >>> 16);
		dest[offset + 6] = (byte) (value >>> 8);
		dest[offset + 7] = (byte) value;
	}

	private static long readLong(byte[] src, int offset) {
		return (((long) src[offset] & 0xff) << 56)
				| (((long) src[offset + 1] & 0xff) << 48)
				| (((long) src[offset + 2] & 0xff) << 40)
				| (((long) src[offset + 3] & 0xff) << 32)
				| (((long) src[offset + 4] & 0xff) << 24)
				| (((long) src[offset + 5] & 0xff) << 16)
				| (((long) src[offset + 6] & 0xff) << 8)
				| (((long) src[offset + 7] & 0xff));
	}
}
