package com.lakeserl.mini_banking.shared.security;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.KeyLengthException;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Local development HS256 JWT issuer for manual testing and frontend integration.
 * Active only when profile {@code dev} is active without {@code prod},
 * {@code security.dev-auth.enabled=true}, and {@code security.jwt.secret} is configured.
 */
@RestController
@RequestMapping("/api/dev/auth")
@Profile("dev & !prod")
@ConditionalOnProperty(name = "security.dev-auth.enabled", havingValue = "true")
@Conditional(HasJwtSecretCondition.class)
public class DevAuthController {

	private static final int MIN_HS256_SECRET_BYTES = 32;
	private static final long DEFAULT_TTL_SECONDS = 3600L;
	private static final long MAX_TTL_SECONDS = 86_400L;
	private static final List<String> ALLOWED_ROLES = List.of("USER", "ADMIN");

	public record DevTokenRequest(UUID userId, List<String> roles, Long ttlSeconds) {
	}

	public record DevTokenResponse(String accessToken, String tokenType, UUID userId, List<String> roles,
			Instant expiresAt) {
		@Override
		public String toString() {
			return "DevTokenResponse[accessToken=[REDACTED], tokenType=" + tokenType
					+ ", userId=" + userId + ", roles=" + roles + ", expiresAt=" + expiresAt + "]";
		}
	}

	private final MACSigner signer;
	private final Clock clock;

	@Autowired
	public DevAuthController(Environment environment, ObjectProvider<Clock> clockProvider) {
		this(Objects.requireNonNull(environment, "environment must not be null")
				.getRequiredProperty(HasJwtSecretCondition.JWT_SECRET_PROPERTY), clockProvider);
	}

	DevAuthController(String secret, ObjectProvider<Clock> clockProvider) {
		Objects.requireNonNull(secret, "security.jwt.secret must not be null");
		Objects.requireNonNull(clockProvider, "clockProvider must not be null");
		byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < MIN_HS256_SECRET_BYTES) {
			throw new IllegalArgumentException(
					"security.jwt.secret must be at least " + MIN_HS256_SECRET_BYTES + " bytes for HS256");
		}
		try {
			this.signer = new MACSigner(bytes);
		} catch (KeyLengthException e) {
			throw new IllegalArgumentException("Invalid HS256 signing key", e);
		}
		this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
	}

	@PostMapping("/token")
	public DevTokenResponse issueToken(@RequestBody(required = false) DevTokenRequest request) {
		UUID userId = (request != null && request.userId() != null) ? request.userId() : UUID.randomUUID();
		List<String> roles = normalizeRoles(request != null ? request.roles() : null);
		long ttlSeconds = (request != null && request.ttlSeconds() != null) ? request.ttlSeconds() : DEFAULT_TTL_SECONDS;
		if (ttlSeconds < 1 || ttlSeconds > MAX_TTL_SECONDS) {
			throw new IllegalArgumentException("ttlSeconds must be between 1 and " + MAX_TTL_SECONDS);
		}

		Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
		Instant expiresAt = now.plusSeconds(ttlSeconds);

		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.subject(userId.toString())
				.claim("roles", roles)
				.issueTime(Date.from(now))
				.expirationTime(Date.from(expiresAt))
				.build();

		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
		try {
			jwt.sign(signer);
		} catch (JOSEException e) {
			throw new IllegalStateException("Failed to sign dev JWT", e);
		}
		return new DevTokenResponse(jwt.serialize(), "Bearer", userId, roles, expiresAt);
	}

	private static List<String> normalizeRoles(List<String> requested) {
		if (requested == null || requested.isEmpty()) {
			return List.of("USER");
		}
		Set<String> normalized = new LinkedHashSet<>();
		for (String raw : requested) {
			if (raw == null || raw.isBlank()) {
				throw new IllegalArgumentException("roles must not contain blank entries");
			}
			String upper = raw.trim().toUpperCase(Locale.ROOT);
			if (upper.startsWith("ROLE_")) {
				upper = upper.substring("ROLE_".length());
			}
			if (!ALLOWED_ROLES.contains(upper)) {
				throw new IllegalArgumentException("Unsupported role. Allowed roles: " + ALLOWED_ROLES);
			}
			normalized.add(upper);
		}
		return List.copyOf(normalized);
	}
}
