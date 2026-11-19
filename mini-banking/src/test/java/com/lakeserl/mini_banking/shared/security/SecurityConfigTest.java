package com.lakeserl.mini_banking.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

class SecurityConfigTest {

	private static final String SECRET = "unit-test-jwt-secret-key-at-least-32-bytes-long";

	private final SecurityConfig config = new SecurityConfig();

	@Test
	void hmacJwtDecoderVerifiesValidTokenAndRejectsTamperedToken() throws Exception {
		JwtDecoder decoder = config.hmacJwtDecoder(SECRET);
		UUID userId = UUID.randomUUID();

		SignedJWT valid = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
				.subject(userId.toString())
				.claim("roles", List.of("ADMIN"))
				.issueTime(Date.from(Instant.now()))
				.expirationTime(Date.from(Instant.now().plusSeconds(300)))
				.build());
		valid.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));

		Jwt decoded = decoder.decode(valid.serialize());
		assertThat(decoded.getSubject()).isEqualTo(userId.toString());

		JwtAuthenticationConverter converter = config.jwtAuthenticationConverter();
		var auth = converter.convert(decoded);
		assertThat(auth.getAuthorities().stream().map(GrantedAuthority::getAuthority))
				.contains("ROLE_ADMIN");

		SignedJWT wrongKey = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
				.subject(userId.toString())
				.expirationTime(Date.from(Instant.now().plusSeconds(300)))
				.build());
		wrongKey.sign(new MACSigner("different-secret-key-at-least-32-bytes-long-000".getBytes(StandardCharsets.UTF_8)));

		assertThatThrownBy(() -> decoder.decode(wrongKey.serialize())).isInstanceOf(JwtException.class);
	}

	@Test
	void hmacJwtDecoderRejectsShortSecret() {
		assertThatThrownBy(() -> config.hmacJwtDecoder("too-short"))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
