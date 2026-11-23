package com.lakeserl.mini_banking.shared.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.lakeserl.mini_banking.mockbank.MockBankController;
import com.lakeserl.mini_banking.mockbank.MockBankGateway;
import com.lakeserl.mini_banking.shared.api.ApiExceptionHandler;
import com.lakeserl.mini_banking.shared.idempotency.IdempotencyService;

@WebMvcTest(controllers = { MockBankController.class, DevAuthController.class })
@Import({ SecurityConfig.class, ApiExceptionHandler.class })
@ActiveProfiles("prod")
@TestPropertySource(properties = {
		"security.dev-auth.enabled=true",
		"security.jwt.secret=prod-test-jwt-secret-key-at-least-32-bytes-long",
		"PROD_JWT_ISSUER_URI=https://issuer.example.test/realms/mini-banking"
})
class SecurityFilterChainWebTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	MockBankGateway mockBankGateway;

	@MockitoBean
	IdempotencyService idempotencyService;

	@MockitoBean
	JwtDecoder jwtDecoder;

	@Test
	void prodProfileBlocksDevAuthPermitsHealthProbesRestrictsPrometheusToAdminAndDeniesDefaultCors() throws Exception {
		mockMvc.perform(post("/api/dev/auth/token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
		mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isNotFound());
		mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isNotFound());

		mockMvc.perform(get("/actuator/prometheus"))
				.andExpect(status().isUnauthorized());

		mockMvc.perform(get("/actuator/prometheus")
						.with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
				.andExpect(status().isForbidden());

		mockMvc.perform(get("/actuator/prometheus")
						.with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isNotFound());

		mockMvc.perform(options("/api/wallets")
						.header(HttpHeaders.ORIGIN, "http://localhost:3000")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}
}
