package com.lakeserl.mini_banking.shared.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.lakeserl.mini_banking.shared.idempotency.IdempotencyFilter;
import com.lakeserl.mini_banking.shared.idempotency.IdempotencyService;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private static final int MIN_HS256_SECRET_BYTES = 32;

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, IdempotencyService idempotencyService,
			ObjectProvider<DevAuthController> devAuthControllerProvider) {
		boolean allowDevAuth = devAuthControllerProvider.getIfAvailable() != null;
		return http
				.cors(Customizer.withDefaults())
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> {
					auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
					auth.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll();
					auth.requestMatchers("/actuator/**").hasRole("ADMIN");
					if (allowDevAuth) {
						auth.requestMatchers(HttpMethod.POST, "/api/dev/auth/token").permitAll();
					}
					auth.anyRequest().authenticated();
				})
				.oauth2ResourceServer(oauth2 -> oauth2
						.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
				.addFilterAfter(new IdempotencyFilter(idempotencyService), BearerTokenAuthenticationFilter.class)
				.build();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(
			@Value("${security.cors.allowed-origins:}") List<String> allowedOrigins) {
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		List<String> validOrigins = allowedOrigins == null
				? List.of()
				: allowedOrigins.stream()
						.filter(Objects::nonNull)
						.map(String::trim)
						.filter(s -> !s.isBlank())
						.toList();
		for (String origin : validOrigins) {
			validateCorsOrigin(origin);
		}
		CorsConfiguration config = new CorsConfiguration();
		config.setAllowedOrigins(validOrigins);
		config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
		config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
		config.setAllowCredentials(false);
		config.setMaxAge(3600L);
		source.registerCorsConfiguration("/api/**", config);
		return source;
	}

	@Bean
	JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtGrantedAuthoritiesConverter scopesConverter = new JwtGrantedAuthoritiesConverter();
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(jwt -> {
			Collection<GrantedAuthority> authorities = new ArrayList<>(scopesConverter.convert(jwt));
			List<String> roles = jwt.getClaimAsStringList("roles");
			if (roles != null) {
				for (String role : roles) {
					if (role != null && !role.isBlank()) {
						String trimmed = role.trim();
						String normalized = trimmed.startsWith("ROLE_") ? trimmed : "ROLE_" + trimmed;
						authorities.add(new SimpleGrantedAuthority(normalized));
					}
				}
			}
			return authorities;
		});
		return converter;
	}

	/**
	 * Optional HS256 decoder for non-prod environments without an external JWKS endpoint.
	 * Production deployments should omit {@code security.jwt.secret} and configure
	 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} or {@code jwk-set-uri}.
	 */
	@Bean
	@Profile("!prod")
	@Conditional(HasJwtSecretCondition.class)
	@ConditionalOnMissingBean(JwtDecoder.class)
	JwtDecoder hmacJwtDecoder(Environment environment) {
		return buildHmacJwtDecoder(environment.getRequiredProperty(HasJwtSecretCondition.JWT_SECRET_PROPERTY));
	}

	static JwtDecoder buildHmacJwtDecoder(String secret) {
		Objects.requireNonNull(secret, "security.jwt.secret must not be null");
		byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
		if (keyBytes.length < MIN_HS256_SECRET_BYTES) {
			throw new IllegalArgumentException(
					"security.jwt.secret must be at least " + MIN_HS256_SECRET_BYTES + " bytes for HS256");
		}
		SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA256");
		return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
	}

	private static void validateCorsOrigin(String origin) {
		if (origin.contains("*") || "null".equalsIgnoreCase(origin)) {
			throw new IllegalArgumentException("Disallowed CORS origin: " + origin);
		}
		final URI uri;
		try {
			uri = new URI(origin);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Invalid CORS origin syntax: " + origin, e);
		}
		boolean validScheme = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
		boolean hasPathOrQuery = (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
				|| uri.getRawQuery() != null
				|| uri.getRawFragment() != null
				|| uri.getRawUserInfo() != null;
		if (!validScheme || uri.getHost() == null || hasPathOrQuery) {
			throw new IllegalArgumentException("CORS origin must be scheme://host[:port] without path: " + origin);
		}
	}
}
