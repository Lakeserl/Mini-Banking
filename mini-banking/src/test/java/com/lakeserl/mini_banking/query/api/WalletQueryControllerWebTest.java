package com.lakeserl.mini_banking.query.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

import com.lakeserl.mini_banking.query.api.QueryRepository.DailyReportRow;
import com.lakeserl.mini_banking.query.api.QueryRepository.TransactionHistoryRow;
import com.lakeserl.mini_banking.query.api.QueryRepository.WalletBalanceRow;
import com.lakeserl.mini_banking.shared.api.ApiExceptionHandler;
import com.lakeserl.mini_banking.shared.idempotency.IdempotencyService;
import com.lakeserl.mini_banking.shared.security.SecurityConfig;

@WebMvcTest(controllers = WalletQueryController.class)
@Import({ SecurityConfig.class, ApiExceptionHandler.class })
class WalletQueryControllerWebTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	QueryRepository queryRepository;

	@MockitoBean
	IdempotencyService idempotencyService;

	@MockitoBean
	JwtDecoder jwtDecoder;

	@Test
	void getBalanceReturns200ForOwnerAndAdmin403ForStrangerAnd404WhenMissing() throws Exception {
		UUID walletId = UUID.randomUUID();
		UUID ownerId = UUID.randomUUID();
		UUID strangerId = UUID.randomUUID();
		UUID adminId = UUID.randomUUID();
		Instant now = Instant.parse("2026-11-21T08:00:00Z");

		when(queryRepository.findBalance(walletId)).thenReturn(Optional.of(
				new WalletBalanceRow(walletId, ownerId, "VND", 1_500_000L, "ACTIVE", 5L, now)));

		mockMvc.perform(get("/api/wallets/{id}/balance", walletId)
						.with(jwt().jwt(j -> j.subject(ownerId.toString()))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.walletId").value(walletId.toString()))
				.andExpect(jsonPath("$.balance").value(1_500_000L))
				.andExpect(jsonPath("$.version").value(5L));

		mockMvc.perform(get("/api/wallets/{id}/balance", walletId)
						.with(jwt().jwt(j -> j.subject(adminId.toString()))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balance").value(1_500_000L));

		mockMvc.perform(get("/api/wallets/{id}/balance", walletId)
						.with(jwt().jwt(j -> j.subject(strangerId.toString()))))
				.andExpect(status().isForbidden());

		UUID missingId = UUID.randomUUID();
		when(queryRepository.findBalance(missingId)).thenReturn(Optional.empty());
		mockMvc.perform(get("/api/wallets/{id}/balance", missingId)
						.with(jwt().jwt(j -> j.subject(ownerId.toString()))))
				.andExpect(status().isNotFound());
	}

	@Test
	void getTransactionsSupportsCursorPaginationAndRejectsBadCursorOrLimit() throws Exception {
		UUID walletId = UUID.randomUUID();
		UUID ownerId = UUID.randomUUID();
		Instant t1 = Instant.parse("2026-11-21T09:00:00Z");
		Instant t2 = Instant.parse("2026-11-21T08:00:00Z");
		Instant t3 = Instant.parse("2026-11-21T07:00:00Z");

		when(queryRepository.findBalance(walletId)).thenReturn(Optional.of(
				new WalletBalanceRow(walletId, ownerId, "VND", 500_000L, "ACTIVE", 3L, t1)));

		TransactionHistoryRow r1 = new TransactionHistoryRow(30L, UUID.randomUUID(), walletId, "TRANSFER", "OUT",
				100_000L, "VND", "COMPLETED", UUID.randomUUID(), null, null, null, null, t1, t1);
		TransactionHistoryRow r2 = new TransactionHistoryRow(20L, UUID.randomUUID(), walletId, "DEPOSIT", "IN",
				500_000L, "VND", "CONFIRMED", null, "BANK_SETTLEMENT", "REF-1", null, null, t2, t2);
		TransactionHistoryRow r3 = new TransactionHistoryRow(10L, UUID.randomUUID(), walletId, "WITHDRAWAL", "OUT",
				50_000L, "VND", "FAILED", null, "****1234", null, null, "rejected", t3, t3);

		// limit=2 => repository asked for 3 (limit+1); returning 3 rows means hasMore=true
		when(queryRepository.findTransactions(eq(walletId), isNull(), eq(3))).thenReturn(List.of(r1, r2, r3));

		mockMvc.perform(get("/api/wallets/{id}/transactions", walletId)
						.param("limit", "2")
						.with(jwt().jwt(j -> j.subject(ownerId.toString()))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.hasMore").value(true))
				.andExpect(jsonPath("$.nextCursor").value(CursorCodec.encode(t2, 20L)));

		mockMvc.perform(get("/api/wallets/{id}/transactions", walletId)
						.param("cursor", "!!!bad-cursor!!!")
						.with(jwt().jwt(j -> j.subject(ownerId.toString()))))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/wallets/{id}/transactions", walletId)
						.param("limit", "101")
						.with(jwt().jwt(j -> j.subject(ownerId.toString()))))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/wallets/{id}/transactions", walletId)
						.param("limit", "0")
						.with(jwt().jwt(j -> j.subject(ownerId.toString()))))
				.andExpect(status().isBadRequest());
	}

	@Test
	void getDailyReportsRequiresAdminAndValidDateRange() throws Exception {
		UUID userId = UUID.randomUUID();
		LocalDate from = LocalDate.of(2026, 11, 1);
		LocalDate to = LocalDate.of(2026, 11, 21);

		when(queryRepository.findDailyReports(from, to)).thenReturn(List.of(
				new DailyReportRow(LocalDate.of(2026, 11, 21), "VND", 1_000_000L, 200_000L, 500_000L, 0L, 4L)));

		mockMvc.perform(get("/api/reports/daily")
						.param("from", "2026-11-01")
						.param("to", "2026-11-21")
						.with(jwt().jwt(j -> j.subject(userId.toString()))))
				.andExpect(status().isForbidden());

		mockMvc.perform(get("/api/reports/daily")
						.param("from", "2026-11-22")
						.param("to", "2026-11-21")
						.with(jwt().jwt(j -> j.subject(userId.toString()))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/reports/daily")
						.param("from", "2026-01-01")
						.param("to", "2026-11-21")
						.with(jwt().jwt(j -> j.subject(userId.toString()))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isBadRequest());

		mockMvc.perform(get("/api/reports/daily")
						.param("from", "2026-11-01")
						.param("to", "2026-11-21")
						.with(jwt().jwt(j -> j.subject(userId.toString()))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].date").value("2026-11-21"))
				.andExpect(jsonPath("$[0].totalDeposit").value(1_000_000L))
				.andExpect(jsonPath("$[0].transactionCount").value(4L));
	}
}
