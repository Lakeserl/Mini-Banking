package com.lakeserl.mini_banking.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.lakeserl.mini_banking.mockbank.MockBankController;
import com.lakeserl.mini_banking.mockbank.MockBankGateway;
import com.lakeserl.mini_banking.shared.api.ApiExceptionHandler;
import com.lakeserl.mini_banking.shared.idempotency.IdempotencyService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(controllers = { DevAuthController.class, MockBankController.class })
@Import({ SecurityConfig.class, ApiExceptionHandler.class })
@ActiveProfiles("dev")
@TestPropertySource(properties = {
		"security.dev-auth.enabled=true",
		"security.jwt.secret=dev-test-jwt-secret-key-at-least-32-bytes-long",
		"security.cors.allowed-origins=http://localhost:3000"
})
class DevAuthControllerWebTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JwtDecoder jwtDecoder;

	@MockitoBean
	MockBankGateway mockBankGateway;

	@MockitoBean
	IdempotencyService idempotencyService;

	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void issuesValidHs256JwtForDefaultAndAdminRequests() throws Exception {
		MvcResult defaultResult = mockMvc.perform(post("/api/dev/auth/token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.roles[0]").value("USER"))
				.andReturn();

		JsonNode defaultBody = mapper.readTree(defaultResult.getResponse().getContentAsString());
		String defaultToken = defaultBody.get("accessToken").asString();
		Jwt defaultJwt = jwtDecoder.decode(defaultToken);
		assertThat(defaultJwt.getSubject()).isEqualTo(defaultBody.get("userId").asString());
		assertThat(defaultJwt.getClaimAsStringList("roles")).containsExactly("USER");
		assertThat(defaultJwt.getExpiresAt()).isEqualTo(defaultJwt.getIssuedAt().plusSeconds(3600L));

		mockMvc.perform(get("/api/mock-bank/transactions/REF-UNKNOWN")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + defaultToken))
				.andExpect(status().isForbidden());

		mockMvc.perform(get("/api/dev/auth/token"))
				.andExpect(status().isUnauthorized());

		UUID adminId = UUID.randomUUID();
		MvcResult adminResult = mockMvc.perform(post("/api/dev/auth/token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\":\"" + adminId + "\",\"roles\":[\"USER\",\"ADMIN\"],\"ttlSeconds\":600}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(adminId.toString()))
				.andReturn();

		JsonNode adminBody = mapper.readTree(adminResult.getResponse().getContentAsString());
		String adminToken = adminBody.get("accessToken").asString();
		Jwt adminJwt = jwtDecoder.decode(adminToken);
		assertThat(adminJwt.getSubject()).isEqualTo(adminId.toString());
		assertThat(adminJwt.getClaimAsStringList("roles")).containsExactly("USER", "ADMIN");
		assertThat(Instant.parse(adminBody.get("expiresAt").asString())).isEqualTo(adminJwt.getExpiresAt());

		mockMvc.perform(get("/api/mock-bank/transactions/REF-UNKNOWN")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
				.andExpect(status().isNotFound());

		mockMvc.perform(post("/api/dev/auth/token"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roles[0]").value("USER"));

		mockMvc.perform(post("/api/dev/auth/token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"roles\":[]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roles[0]").value("USER"));

		mockMvc.perform(post("/api/dev/auth/token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"roles\":[\"role_admin\"]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roles[0]").value("ADMIN"));
	}

	@Test
	void rejectsDisallowedBlankNullRolesOutOfRangeTtlAndShortSecretAndNormalizesLocaleSafely() throws Exception {
		mockMvc.perform(post("/api/dev/auth/token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"roles\":[\"SUPERADMIN\"]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

		mockMvc.perform(post("/api/dev/auth/token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"roles\":[\"\"]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

		mockMvc.perform(post("/api/dev/auth/token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"roles\":[null]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

		mockMvc.perform(post("/api/dev/auth/token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ttlSeconds\":0}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

		mockMvc.perform(post("/api/dev/auth/token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ttlSeconds\":86401}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

		@SuppressWarnings("unchecked")
		ObjectProvider<Clock> fixedClockProvider = org.mockito.Mockito.mock(ObjectProvider.class);
		org.mockito.Mockito.when(fixedClockProvider.getIfAvailable(org.mockito.ArgumentMatchers.any()))
				.thenReturn(Clock.fixed(Instant.parse("2026-11-23T10:00:00.987654321Z"), ZoneOffset.UTC));

		assertThatThrownBy(() -> new DevAuthController("too-short", fixedClockProvider))
				.isInstanceOf(IllegalArgumentException.class);

		Locale prev = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			DevAuthController controller = new DevAuthController(
					"dev-test-jwt-secret-key-at-least-32-bytes-long", fixedClockProvider);
			var response = controller.issueToken(new DevAuthController.DevTokenRequest(null, List.of("admin"), 60L));
			assertThat(response.roles()).containsExactly("ADMIN");
			assertThat(response.expiresAt()).isEqualTo(Instant.parse("2026-11-23T10:01:00Z"));
		} finally {
			Locale.setDefault(prev);
		}
	}

	@Test
	void corsPreflightAllowsConfiguredLocalhostOriginAndRejectsUnknownOrigin() throws Exception {
		mockMvc.perform(options("/api/wallets")
						.header(HttpHeaders.ORIGIN, "http://localhost:3000")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type, Idempotency-Key"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"));

		mockMvc.perform(options("/api/wallets")
						.header(HttpHeaders.ORIGIN, "https://evil.example")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}
}
