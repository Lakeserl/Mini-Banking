package com.lakeserl.mini_banking.mockbank;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.lakeserl.mini_banking.mockbank.MockBankGateway.BankTransaction;
import com.lakeserl.mini_banking.shared.api.ApiExceptionHandler;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.idempotency.IdempotencyService;
import com.lakeserl.mini_banking.shared.security.SecurityConfig;

@WebMvcTest(controllers = MockBankController.class)
@Import({ SecurityConfig.class, ApiExceptionHandler.class })
class MockBankControllerWebTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	MockBankGateway mockBankGateway;

	@MockitoBean
	IdempotencyService idempotencyService;

	@MockitoBean
	JwtDecoder jwtDecoder;

	@Test
	void nonAdminReceives403WhenQueryingMockBankTransaction() throws Exception {
		String userId = UUID.randomUUID().toString();
		when(mockBankGateway.findTransaction("BANK-REF-1")).thenReturn(Optional.of(new BankTransaction(
				"BANK-REF-1", "DEPOSIT", UUID.randomUUID(), Money.vnd(100_000), "SUCCESS", null, Instant.now())));

		mockMvc.perform(get("/api/mock-bank/transactions/BANK-REF-1")
						.with(jwt().jwt(j -> j.subject(userId))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("FORBIDDEN"));
	}

	@Test
	void adminCanQueryExistingOrMissingMockBankTransaction() throws Exception {
		String adminId = UUID.randomUUID().toString();
		UUID txId = UUID.randomUUID();
		when(mockBankGateway.findTransaction("BANK-REF-1")).thenReturn(Optional.of(new BankTransaction(
				"BANK-REF-1", "DEPOSIT", txId, Money.vnd(250_000), "SUCCESS", null, Instant.parse("2026-11-21T10:00:00Z"))));
		when(mockBankGateway.findTransaction("BANK-REF-MISSING")).thenReturn(Optional.empty());

		mockMvc.perform(get("/api/mock-bank/transactions/BANK-REF-1")
						.with(jwt().jwt(j -> j.subject(adminId))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.bankReference").value("BANK-REF-1"))
				.andExpect(jsonPath("$.transactionId").value(txId.toString()));

		mockMvc.perform(get("/api/mock-bank/transactions/BANK-REF-MISSING")
						.with(jwt().jwt(j -> j.subject(adminId))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isNotFound());
	}
}
