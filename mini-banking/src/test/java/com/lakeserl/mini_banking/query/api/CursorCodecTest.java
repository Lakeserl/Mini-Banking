package com.lakeserl.mini_banking.query.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class CursorCodecTest {

	@Test
	void encodeAndDecodeRoundTripPreservesInstantAndId() {
		Instant occurredAt = Instant.parse("2026-11-21T08:15:30.123456Z");
		String encoded = CursorCodec.encode(occurredAt, 42L);
		CursorCodec.Cursor decoded = CursorCodec.decode(encoded);

		assertThat(decoded.occurredAt()).isEqualTo(occurredAt);
		assertThat(decoded.id()).isEqualTo(42L);
	}

	@Test
	void decodeRejectsBlankMalformedTruncatedAndNonPositiveId() {
		assertThatThrownBy(() -> CursorCodec.decode("")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CursorCodec.decode("   ")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CursorCodec.decode("!!!not-base64!!!")).isInstanceOf(IllegalArgumentException.class);

		String noDelimiter = Base64.getUrlEncoder().withoutPadding().encodeToString("2026-11-21T00:00:00Z".getBytes());
		assertThatThrownBy(() -> CursorCodec.decode(noDelimiter)).isInstanceOf(IllegalArgumentException.class);

		String badId = Base64.getUrlEncoder().withoutPadding().encodeToString("2026-11-21T00:00:00Z|0".getBytes());
		assertThatThrownBy(() -> CursorCodec.decode(badId)).isInstanceOf(IllegalArgumentException.class);

		String badTime = Base64.getUrlEncoder().withoutPadding().encodeToString("not-an-instant|10".getBytes());
		assertThatThrownBy(() -> CursorCodec.decode(badTime)).isInstanceOf(IllegalArgumentException.class);
	}
}
