package com.lakeserl.mini_banking.shared.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

import com.lakeserl.mini_banking.shared.idempotency.IdempotencyFilter;
import com.lakeserl.mini_banking.shared.idempotency.IdempotencyService;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private static final int MIN_HS256_SECRET_BYTES = 32;

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, IdempotencyService idempotencyService) {
		return http
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
						.anyRequest().authenticated())
				.oauth2ResourceServer(oauth2 -> oauth2
						.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
				.addFilterAfter(new IdempotencyFilter(idempotencyService), BearerTokenAuthenticationFilter.class)
				.build();
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
						String normalized = role.startsWith("ROLE_") ? role : "ROLE_" + role;
						authorities.add(new SimpleGrantedAuthority(normalized));
					}
				}
			}
			return authorities;
		});
		return converter;
	}

	/**
	 * Optional HS256 decoder for local/dev environments without an external JWKS endpoint.
	 * Production deployments should omit {@code security.jwt.secret} and configure
	 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} or {@code jwk-set-uri}.
	 */
	@Bean
	@ConditionalOnExpression("!'${security.jwt.secret:}'.isBlank()")
	@ConditionalOnMissingBean(JwtDecoder.class)
	JwtDecoder hmacJwtDecoder(@Value("${security.jwt.secret}") String secret) {
		byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
		if (keyBytes.length < MIN_HS256_SECRET_BYTES) {
			throw new IllegalArgumentException(
					"security.jwt.secret must be at least " + MIN_HS256_SECRET_BYTES + " bytes for HS256");
		}
		SecretKey key = new SecretKeySpec(keyBytes, "HmacSHA256");
		return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
	}
}
