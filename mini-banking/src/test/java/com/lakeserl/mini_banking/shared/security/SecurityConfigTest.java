package com.lakeserl.mini_banking.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import com.lakeserl.mini_banking.shared.idempotency.IdempotencyService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

class SecurityConfigTest {

	private static final String SECRET = "unit-test-jwt-secret-key-at-least-32-bytes-long";
	private static final String SECRET_WITH_QUOTES_AND_SPEL = "dev-secret-'with'-quotes-and-#{1+1}-32bytes!!";

	private final SecurityConfig config = new SecurityConfig();

	@Test
	void hmacJwtDecoderVerifiesValidTokenRejectsTamperedOrExpiredAndConvertsAuthorities() throws Exception {
		JwtDecoder decoder = SecurityConfig.buildHmacJwtDecoder(SECRET);
		UUID userId = UUID.randomUUID();

		SignedJWT valid = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
				.subject(userId.toString())
				.claim("roles", Arrays.asList("ADMIN", "ROLE_AUDITOR", "  USER ", "", null))
				.claim("scope", "wallets:read")
				.issueTime(Date.from(Instant.now()))
				.expirationTime(Date.from(Instant.now().plusSeconds(300)))
				.build());
		valid.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));

		Jwt decoded = decoder.decode(valid.serialize());
		assertThat(decoded.getSubject()).isEqualTo(userId.toString());

		JwtAuthenticationConverter converter = config.jwtAuthenticationConverter();
		var auth = converter.convert(decoded);
		List<String> authorities = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
		assertThat(authorities)
				.contains("ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_USER", "SCOPE_wallets:read")
				.doesNotContain("ROLE_ROLE_AUDITOR", "ROLE_");

		SignedJWT noRoles = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
				.subject(userId.toString())
				.expirationTime(Date.from(Instant.now().plusSeconds(300)))
				.build());
		noRoles.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
		assertThat(converter.convert(decoder.decode(noRoles.serialize())).getAuthorities().stream()
				.map(GrantedAuthority::getAuthority))
				.noneMatch(a -> a.startsWith("ROLE_"));

		SignedJWT wrongKey = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
				.subject(userId.toString())
				.expirationTime(Date.from(Instant.now().plusSeconds(300)))
				.build());
		wrongKey.sign(new MACSigner("different-secret-key-at-least-32-bytes-long-000".getBytes(StandardCharsets.UTF_8)));
		assertThatThrownBy(() -> decoder.decode(wrongKey.serialize())).isInstanceOf(JwtException.class);

		SignedJWT expired = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
				.subject(userId.toString())
				.expirationTime(Date.from(Instant.now().minusSeconds(120)))
				.build());
		expired.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
		assertThatThrownBy(() -> decoder.decode(expired.serialize())).isInstanceOf(JwtException.class);
	}

	@Test
	void hmacJwtDecoderEnforcesExact32ByteBoundary() {
		assertThatThrownBy(() -> SecurityConfig.buildHmacJwtDecoder("a".repeat(31)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(SecurityConfig.buildHmacJwtDecoder("a".repeat(32))).isNotNull();
	}

	@Test
	void jwtAuthenticationConverterExtractsKeycloakRealmAccessRolesAndIgnoresMalformedClaims() {
		JwtAuthenticationConverter converter = config.jwtAuthenticationConverter();
		Instant now = Instant.now();

		Jwt keycloakJwt = Jwt.withTokenValue("tok-kc")
				.header("alg", "RS256")
				.subject(UUID.randomUUID().toString())
				.issuedAt(now)
				.expiresAt(now.plusSeconds(300))
				.claim("realm_access", Map.of("roles", Arrays.asList("admin", "ROLE_user", "ROLE_ teller", "ROLE_   ", "role_", "  ", 123, null)))
				.claim("roles", List.of("ADMIN"))
				.claim("scope", "openid profile")
				.build();

		List<String> authorities = converter.convert(keycloakJwt).getAuthorities().stream()
				.map(GrantedAuthority::getAuthority)
				.toList();
		assertThat(authorities)
				.contains("ROLE_ADMIN", "ROLE_USER", "ROLE_TELLER", "SCOPE_openid", "SCOPE_profile")
				.doesNotContain("ROLE_admin", "ROLE_ROLE_USER", "ROLE_ TELLER", "ROLE_   ", "ROLE_");
		assertThat(authorities.stream().filter("ROLE_ADMIN"::equals).count()).isEqualTo(1L);

		Jwt malformedJwt = Jwt.withTokenValue("tok-bad")
				.header("alg", "RS256")
				.subject(UUID.randomUUID().toString())
				.issuedAt(now)
				.expiresAt(now.plusSeconds(300))
				.claim("realm_access", "not-a-map")
				.claim("roles", "not-a-list")
				.build();
		assertThat(converter.convert(malformedJwt).getAuthorities().stream()
				.map(GrantedAuthority::getAuthority))
				.noneMatch(a -> a.startsWith("ROLE_"));
	}

	@Test
	void corsConfigurationSourceDefaultsToClosedValidatesStrictOriginsAndScopesToApi() {
		var closed = config.corsConfigurationSource(List.of());
		var apiRequest = new MockHttpServletRequest("GET", "/api/wallets");
		assertThat(closed.getCorsConfiguration(apiRequest)).isNotNull();
		assertThat(closed.getCorsConfiguration(apiRequest).getAllowedOrigins()).isEmpty();
		assertThat(closed.getCorsConfiguration(new MockHttpServletRequest("GET", "/actuator/health"))).isNull();

		var nullList = config.corsConfigurationSource(null);
		assertThat(nullList.getCorsConfiguration(apiRequest).getAllowedOrigins()).isEmpty();

		var dev = config.corsConfigurationSource(Arrays.asList(" http://localhost:3000 ", null, "  ", "https://app.example.com"));
		var corsConfig = dev.getCorsConfiguration(apiRequest);
		assertThat(corsConfig).isNotNull();
		assertThat(corsConfig.getAllowedOrigins()).containsExactly("http://localhost:3000", "https://app.example.com");
		assertThat(corsConfig.getAllowedMethods()).containsExactlyInAnyOrder("GET", "POST", "OPTIONS");
		assertThat(corsConfig.getAllowedHeaders())
				.containsExactlyInAnyOrder("Authorization", "Content-Type", "Idempotency-Key");
		assertThat(corsConfig.getAllowCredentials()).isFalse();
		assertThat(corsConfig.getMaxAge()).isEqualTo(3600L);

		assertThatThrownBy(() -> config.corsConfigurationSource(List.of("*")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> config.corsConfigurationSource(List.of("https://*.example.com")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> config.corsConfigurationSource(List.of("null")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> config.corsConfigurationSource(List.of("http://localhost:3000/")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> config.corsConfigurationSource(List.of("javascript://example.com")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void devAuthAndHmacDecoderPreserveLiteralSpelCharactersExcludeProdAndRespectBackoff() throws Exception {
		ApplicationContextRunner runner = new ApplicationContextRunner()
				.withUserConfiguration(DevAuthController.class, SecurityConfig.class)
				.withBean(IdempotencyService.class, () -> Mockito.mock(IdempotencyService.class));

		runner.withPropertyValues(
						"spring.profiles.active=dev",
						"security.dev-auth.enabled=true",
						"security.jwt.secret=" + SECRET_WITH_QUOTES_AND_SPEL)
				.run(ctx -> {
					assertThat(ctx).hasNotFailed();
					DevAuthController controller = ctx.getBean(DevAuthController.class);
					JwtDecoder decoder = ctx.getBean(JwtDecoder.class);

					// Prove #{1+1} was NOT evaluated by SpEL: raw literal bytes sign & verify cleanly
					var minted = controller.issueToken(new DevAuthController.DevTokenRequest(null, List.of("USER"), 60L));
					assertThat(minted.toString()).doesNotContain(minted.accessToken()).contains("[REDACTED]");
					assertThat(decoder.decode(minted.accessToken()).getSubject()).isEqualTo(minted.userId().toString());

					SignedJWT rawSigned = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
							.subject(UUID.randomUUID().toString())
							.expirationTime(Date.from(Instant.now().plusSeconds(60)))
							.build());
					rawSigned.sign(new MACSigner(SECRET_WITH_QUOTES_AND_SPEL.getBytes(StandardCharsets.UTF_8)));
					assertThat(decoder.decode(rawSigned.serialize())).isNotNull();
				});

		runner.withPropertyValues(
						"spring.profiles.active=dev",
						"security.dev-auth.enabled=false",
						"security.jwt.secret=" + SECRET)
				.run(ctx -> {
					assertThat(ctx).hasNotFailed();
					assertThat(ctx).doesNotHaveBean(DevAuthController.class);
					assertThat(ctx).hasSingleBean(JwtDecoder.class);
				});

		JwtDecoder externalDecoder = Mockito.mock(JwtDecoder.class);
		runner.withBean("externalJwtDecoder", JwtDecoder.class, () -> externalDecoder)
				.withPropertyValues(
						"spring.profiles.active=prod",
						"security.dev-auth.enabled=true",
						"security.jwt.secret=" + SECRET)
				.run(ctx -> {
					assertThat(ctx).hasNotFailed();
					assertThat(ctx).doesNotHaveBean(DevAuthController.class);
					assertThat(ctx.getBean(JwtDecoder.class)).isSameAs(externalDecoder);
				});

		runner.withBean("externalJwtDecoder", JwtDecoder.class, () -> externalDecoder)
				.withPropertyValues(
						"spring.profiles.active=dev,prod",
						"security.dev-auth.enabled=true",
						"security.jwt.secret=" + SECRET)
				.run(ctx -> {
					assertThat(ctx).hasNotFailed();
					assertThat(ctx).doesNotHaveBean(DevAuthController.class);
					assertThat(ctx.getBean(JwtDecoder.class)).isSameAs(externalDecoder);
				});

		runner.withBean("externalJwtDecoder", JwtDecoder.class, () -> externalDecoder)
				.withPropertyValues(
						"spring.profiles.active=dev",
						"security.dev-auth.enabled=false",
						"security.jwt.secret=" + SECRET)
				.run(ctx -> {
					assertThat(ctx).hasNotFailed();
					assertThat(ctx.getBean(JwtDecoder.class)).isSameAs(externalDecoder);
				});

		new ApplicationContextRunner()
				.withUserConfiguration(DevAuthController.class)
				.withPropertyValues("spring.profiles.active=dev", "security.dev-auth.enabled=true")
				.run(ctx -> {
					assertThat(ctx).hasNotFailed();
					assertThat(ctx).doesNotHaveBean(DevAuthController.class);
				});

		runner.withPropertyValues(
						"spring.profiles.active=dev",
						"security.dev-auth.enabled=true",
						"security.jwt.secret=   ")
				.run(ctx -> {
					assertThat(ctx).hasFailed();
					assertThat(ctx.getStartupFailure())
							.hasMessageContaining("No qualifying bean of type 'org.springframework.security.oauth2.jwt.JwtDecoder'");
				});
	}

	@Test
	void applicationYamlPropertiesEnforceProbesGracefulShutdownClosedCorsAndProdDevAuthLockdown() {
		Properties base = loadYamlProperties("application.yaml");
		Properties dev = loadYamlProperties("application-dev.yaml");
		Properties prod = loadYamlProperties("application-prod.yaml");

		assertThat(base.getProperty("server.shutdown")).isEqualTo("graceful");
		assertThat(base.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("30s");
		assertThat(base.getProperty("spring.profiles.default")).isNull();
		assertThat(base.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
		assertThat(base.getProperty("management.endpoint.health.group.readiness.include"))
				.isEqualTo("readinessState,db");
		assertThat(base.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
		assertThat(base.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health,prometheus");
		assertThat(base.getProperty("security.cors.allowed-origins")).isEqualTo("${CORS_ALLOWED_ORIGINS:}");
		assertThat(base.getProperty("security.dev-auth.enabled")).isEqualTo("false");

		assertThat(dev.getProperty("security.cors.allowed-origins"))
				.isEqualTo("${CORS_ALLOWED_ORIGINS:http://localhost:3000}");
		assertThat(dev.getProperty("security.dev-auth.enabled")).isEqualTo("true");

		assertThat(prod.getProperty("spring.flyway.enabled")).isEqualTo("false");
		assertThat(prod.getProperty("spring.flyway.user")).isNull();
		assertThat(prod.getProperty("spring.flyway.password")).isNull();
		assertThat(prod.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"))
				.isEqualTo("${PROD_JWT_ISSUER_URI}");
		assertThat(prod.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri")).isNull();
		assertThat(prod.getProperty("security.dev-auth.enabled")).isEqualTo("false");
	}

	private static Properties loadYamlProperties(String resourcePath) {
		YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
		factory.setResources(new ClassPathResource(resourcePath));
		Properties props = factory.getObject();
		assertThat(props).as("YAML resource %s must load", resourcePath).isNotNull();
		return props;
	}
}
