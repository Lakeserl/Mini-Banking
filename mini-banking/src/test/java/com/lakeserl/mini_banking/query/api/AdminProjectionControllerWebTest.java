package com.lakeserl.mini_banking.query.api;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.lakeserl.mini_banking.query.rebuild.ProjectionRebuildService;
import com.lakeserl.mini_banking.shared.api.ApiExceptionHandler;
import com.lakeserl.mini_banking.shared.idempotency.IdempotencyService;
import com.lakeserl.mini_banking.shared.security.SecurityConfig;

@WebMvcTest(controllers = AdminProjectionController.class)
@Import({ SecurityConfig.class, ApiExceptionHandler.class })
class AdminProjectionControllerWebTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	ProjectionRebuildService rebuildService;

	@MockitoBean
	IdempotencyService idempotencyService;

	@MockitoBean
	JwtDecoder jwtDecoder;

	@Test
	void rebuildRequiresAdminRoleReturns200OnSuccessAnd409WhenAlreadyRunning() throws Exception {
		UUID userId = UUID.randomUUID();
		Instant done = Instant.parse("2026-11-21T10:00:00Z");

		mockMvc.perform(post("/api/admin/projections/rebuild")
						.with(jwt().jwt(j -> j.subject(userId.toString()))))
				.andExpect(status().isForbidden());

		when(rebuildService.rebuildAll()).thenReturn(new ProjectionRebuildService.RebuildResult(42L, 120L, done));
		mockMvc.perform(post("/api/admin/projections/rebuild")
						.with(jwt().jwt(j -> j.subject(userId.toString()))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.eventsReplayed").value(42L))
				.andExpect(jsonPath("$.durationMs").value(120L));

		when(rebuildService.rebuildAll())
				.thenThrow(new ProjectionRebuildService.RebuildInProgressException("Rebuild already running"));
		mockMvc.perform(post("/api/admin/projections/rebuild")
						.with(jwt().jwt(j -> j.subject(userId.toString()))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isConflict());
	}
}
