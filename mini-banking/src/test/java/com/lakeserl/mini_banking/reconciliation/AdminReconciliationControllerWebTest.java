package com.lakeserl.mini_banking.reconciliation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.lakeserl.mini_banking.reconciliation.InternalReconciliationService.ReconciliationRunResult;
import com.lakeserl.mini_banking.reconciliation.ReconciliationIssueRepository.ReconciliationIssue;
import com.lakeserl.mini_banking.reconciliation.ReconciliationIssueRepository.ReconciliationIssueAlreadyResolvedException;
import com.lakeserl.mini_banking.reconciliation.ReconciliationIssueRepository.ReconciliationIssueNotFoundException;
import com.lakeserl.mini_banking.shared.api.ApiExceptionHandler;
import com.lakeserl.mini_banking.shared.idempotency.IdempotencyService;
import com.lakeserl.mini_banking.shared.security.SecurityConfig;

@WebMvcTest(controllers = AdminReconciliationController.class)
@Import({ SecurityConfig.class, ApiExceptionHandler.class, AdminReconciliationControllerWebTest.ClockConfig.class })
class AdminReconciliationControllerWebTest {

	static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

	static class ClockConfig {
		@Bean
		Clock clock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	InternalReconciliationService internalService;

	@MockitoBean
	BankReconciliationService bankService;

	@MockitoBean
	ReconciliationIssueRepository issueRepository;

	@MockitoBean
	IdempotencyService idempotencyService;

	@MockitoBean
	JwtDecoder jwtDecoder;

	@Test
	void nonAdminReceives403OnAllReconciliationEndpoints() throws Exception {
		String userId = UUID.randomUUID().toString();

		mockMvc.perform(post("/api/admin/reconciliation/internal/run").with(jwt().jwt(j -> j.subject(userId))))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/admin/reconciliation/bank/run").with(jwt().jwt(j -> j.subject(userId))))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/admin/reconciliation/issues").with(jwt().jwt(j -> j.subject(userId))))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/admin/reconciliation/issues/1/resolve")
						.with(jwt().jwt(j -> j.subject(userId)))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"resolutionNotes\":\"checked\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void adminCanRunReconciliationListIssuesAndResolveWithAuditErrorsHandled() throws Exception {
		String adminId = UUID.randomUUID().toString();

		when(internalService.run()).thenReturn(new ReconciliationRunResult("INTERNAL", 1, NOW));
		mockMvc.perform(post("/api/admin/reconciliation/internal/run")
						.with(jwt().jwt(j -> j.subject(adminId))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.runType").value("INTERNAL"))
				.andExpect(jsonPath("$.issuesOpened").value(1));

		when(bankService.reconcileDate(LocalDate.of(2026, 11, 20)))
				.thenReturn(new ReconciliationRunResult("BANK", 2, NOW));
		mockMvc.perform(post("/api/admin/reconciliation/bank/run")
						.param("date", "2026-11-20")
						.with(jwt().jwt(j -> j.subject(adminId))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.issuesOpened").value(2));

		ReconciliationIssue openIssue = new ReconciliationIssue(5L, ReconciliationIssueType.BANK_MISMATCH,
				"BANK_MISMATCH:1", "{\"kind\":\"EXTRA\"}", ReconciliationIssueStatus.OPEN, NOW, null, null, null);
		when(issueRepository.findByStatus(eq(ReconciliationIssueStatus.OPEN), isNull(), eq(21)))
				.thenReturn(List.of(openIssue));
		mockMvc.perform(get("/api/admin/reconciliation/issues")
						.with(jwt().jwt(j -> j.subject(adminId))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(5))
				.andExpect(jsonPath("$.hasMore").value(false));

		mockMvc.perform(get("/api/admin/reconciliation/issues")
						.param("cursor", "!!!invalid!!!")
						.with(jwt().jwt(j -> j.subject(adminId))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isBadRequest());

		ReconciliationIssue resolved = new ReconciliationIssue(5L, ReconciliationIssueType.BANK_MISMATCH,
				"BANK_MISMATCH:1", "{\"kind\":\"EXTRA\"}", ReconciliationIssueStatus.RESOLVED,
				NOW, NOW, adminId, "Confirmed with bank ops");
		when(issueRepository.resolve(eq(5L), eq(adminId), eq("Confirmed with bank ops"), any()))
				.thenReturn(resolved);
		mockMvc.perform(post("/api/admin/reconciliation/issues/5/resolve")
						.with(jwt().jwt(j -> j.subject(adminId))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"resolutionNotes\":\"Confirmed with bank ops\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RESOLVED"))
				.andExpect(jsonPath("$.resolvedBy").value(adminId));

		mockMvc.perform(post("/api/admin/reconciliation/issues/5/resolve")
						.with(jwt().jwt(j -> j.subject(adminId))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"resolutionNotes\":\"   \"}"))
				.andExpect(status().isBadRequest());

		when(issueRepository.resolve(eq(99L), eq(adminId), eq("notes"), any()))
				.thenThrow(new ReconciliationIssueNotFoundException(99L));
		mockMvc.perform(post("/api/admin/reconciliation/issues/99/resolve")
						.with(jwt().jwt(j -> j.subject(adminId))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"resolutionNotes\":\"notes\"}"))
				.andExpect(status().isNotFound());

		when(issueRepository.resolve(eq(5L), eq(adminId), eq("again"), any()))
				.thenThrow(new ReconciliationIssueAlreadyResolvedException(5L));
		mockMvc.perform(post("/api/admin/reconciliation/issues/5/resolve")
						.with(jwt().jwt(j -> j.subject(adminId))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"resolutionNotes\":\"again\"}"))
				.andExpect(status().isConflict());
	}
}
