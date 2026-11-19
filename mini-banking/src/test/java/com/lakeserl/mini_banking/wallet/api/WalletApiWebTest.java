package com.lakeserl.mini_banking.wallet.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.lakeserl.mini_banking.deposit.command.DepositCommandHandler;
import com.lakeserl.mini_banking.deposit.domain.DepositStatus;
import com.lakeserl.mini_banking.mockbank.MockBankController;
import com.lakeserl.mini_banking.mockbank.MockBankGateway;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.BankTransaction;
import com.lakeserl.mini_banking.shared.api.ApiExceptionHandler;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.idempotency.IdempotencyService;
import com.lakeserl.mini_banking.shared.idempotency.IdempotencyService.Decision;
import com.lakeserl.mini_banking.shared.security.SecurityConfig;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;
import com.lakeserl.mini_banking.withdrawal.command.WithdrawalCommandHandler;
import com.lakeserl.mini_banking.withdrawal.domain.WithdrawalStatus;

@WebMvcTest(controllers = { WalletController.class, MockBankController.class })
@Import({ SecurityConfig.class, ApiExceptionHandler.class })
class WalletApiWebTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	WalletCommandHandler walletCommandHandler;

	@MockitoBean
	WalletRepository walletRepository;

	@MockitoBean
	DepositCommandHandler depositCommandHandler;

	@MockitoBean
	WithdrawalCommandHandler withdrawalCommandHandler;

	@MockitoBean
	MockBankGateway mockBankGateway;

	@MockitoBean
	IdempotencyService idempotencyService;

	@MockitoBean
	JwtDecoder jwtDecoder;

	@Test
	void unauthenticatedRequestReturns401() throws Exception {
		mockMvc.perform(post("/api/wallets"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void openWalletUsesJwtSubjectAsOwner() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID walletId = Wallet.calculateWalletId(userId, Currency.VND);
		when(walletCommandHandler.openWallet(userId, Currency.VND)).thenReturn(walletId);

		mockMvc.perform(post("/api/wallets")
						.with(jwt().jwt(j -> j.subject(userId.toString())))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currency\":\"VND\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.walletId").value(walletId.toString()))
				.andExpect(jsonPath("$.ownerId").value(userId.toString()))
				.andExpect(jsonPath("$.status").value("ACTIVE"));
	}

	@Test
	void depositRequiresIdempotencyKeyHeader() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();

		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.with(jwt().jwt(j -> j.subject(userId.toString())))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":100000,\"currency\":\"VND\",\"bankReference\":\"REF-1\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("INVALID_IDEMPOTENCY_KEY"));
	}

	@Test
	void depositReplaysCachedResponseWithoutInvokingHandler() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		when(idempotencyService.begin(eq(userId), eq("key-1"), anyString()))
				.thenReturn(new Decision.Completed(200, "{\"depositId\":\"abc\",\"status\":\"CONFIRMED\"}"));

		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.with(jwt().jwt(j -> j.subject(userId.toString())))
						.header("Idempotency-Key", "key-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":100000,\"currency\":\"VND\",\"bankReference\":\"REF-1\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"));

		verify(depositCommandHandler, never()).deposit(any(), any(), any(), any());
	}

	@Test
	void depositWithMismatchedIdempotencyPayloadReturns422() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		when(idempotencyService.begin(eq(userId), eq("key-2"), anyString()))
				.thenReturn(new Decision.HashMismatch());

		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.with(jwt().jwt(j -> j.subject(userId.toString())))
						.header("Idempotency-Key", "key-2")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":999999,\"currency\":\"VND\",\"bankReference\":\"REF-1\"}"))
				.andExpect(status().is(422))
				.andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_CONFLICT"));
	}

	@Test
	void depositByNonOwnerReturns403AndReleasesIdempotencyLock() throws Exception {
		UUID ownerId = UUID.randomUUID();
		UUID attackerId = UUID.randomUUID();
		UUID walletId = Wallet.calculateWalletId(ownerId, Currency.VND);
		Wallet wallet = Wallet.open(walletId, ownerId, Currency.VND);

		when(idempotencyService.begin(eq(attackerId), eq("key-3"), anyString())).thenReturn(new Decision.Acquired());
		when(walletRepository.load(walletId)).thenReturn(Optional.of(wallet));

		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.with(jwt().jwt(j -> j.subject(attackerId.toString())))
						.header("Idempotency-Key", "key-3")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":100000,\"currency\":\"VND\",\"bankReference\":\"REF-1\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("FORBIDDEN"));

		verify(depositCommandHandler, never()).deposit(any(), any(), any(), any());
		verify(idempotencyService).release(attackerId, "key-3");
		verify(idempotencyService, never()).complete(any(), any(), anyInt(), any());
	}

	@Test
	void depositByOwnerCompletesAndStoresIdempotencyResult() throws Exception {
		UUID ownerId = UUID.randomUUID();
		UUID walletId = Wallet.calculateWalletId(ownerId, Currency.VND);
		Wallet wallet = Wallet.open(walletId, ownerId, Currency.VND);

		when(idempotencyService.begin(eq(ownerId), eq("key-4"), anyString())).thenReturn(new Decision.Acquired());
		when(walletRepository.load(walletId)).thenReturn(Optional.of(wallet));
		when(depositCommandHandler.deposit(any(), eq(walletId), eq(Money.vnd(100_000)), eq("REF-4")))
				.thenReturn(DepositStatus.CONFIRMED);

		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.with(jwt().jwt(j -> j.subject(ownerId.toString())))
						.header("Idempotency-Key", "key-4")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":100000,\"currency\":\"VND\",\"bankReference\":\"REF-4\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"));

		verify(idempotencyService).complete(eq(ownerId), eq("key-4"), eq(200), anyString());
	}

	@Test
	void withdrawByOwnerSucceeds() throws Exception {
		UUID ownerId = UUID.randomUUID();
		UUID walletId = Wallet.calculateWalletId(ownerId, Currency.VND);
		Wallet wallet = Wallet.open(walletId, ownerId, Currency.VND);

		when(idempotencyService.begin(eq(ownerId), eq("w-1"), anyString())).thenReturn(new Decision.Acquired());
		when(walletRepository.load(walletId)).thenReturn(Optional.of(wallet));
		when(withdrawalCommandHandler.withdraw(any(), eq(walletId), eq(Money.vnd(50_000)), eq("970436-111")))
				.thenReturn(WithdrawalStatus.CONFIRMED);

		mockMvc.perform(post("/api/wallets/{id}/withdraw", walletId)
						.with(jwt().jwt(j -> j.subject(ownerId.toString())))
						.header("Idempotency-Key", "w-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":50000,\"currency\":\"VND\",\"bankAccount\":\"970436-111\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"));
	}

	@Test
	void lockWalletAllowsAdminAndRejectsOtherUsers() throws Exception {
		UUID ownerId = UUID.randomUUID();
		UUID otherUserId = UUID.randomUUID();
		UUID adminId = UUID.randomUUID();
		UUID walletId = Wallet.calculateWalletId(ownerId, Currency.VND);
		Wallet wallet = Wallet.open(walletId, ownerId, Currency.VND);
		when(walletRepository.load(walletId)).thenReturn(Optional.of(wallet));

		mockMvc.perform(post("/api/wallets/{id}/lock", walletId)
						.with(jwt().jwt(j -> j.subject(otherUserId.toString())))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"suspicious\"}"))
				.andExpect(status().isForbidden());

		mockMvc.perform(post("/api/wallets/{id}/lock", walletId)
						.with(jwt().jwt(j -> j.subject(adminId.toString()).claim("roles", List.of("ADMIN")))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"compliance\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("LOCKED"));

		verify(walletCommandHandler).lockWallet(walletId, "compliance");
	}

	@Test
	void mockBankTransactionEndpointReturnsRecordOr404() throws Exception {
		UUID txId = UUID.randomUUID();
		when(mockBankGateway.findTransaction("REF-OK")).thenReturn(Optional.of(
				new BankTransaction("REF-OK", "DEPOSIT", txId, Money.vnd(100_000), "SUCCESS", null, Instant.now())));
		when(mockBankGateway.findTransaction("UNKNOWN")).thenReturn(Optional.empty());

		mockMvc.perform(get("/api/mock-bank/transactions/{ref}", "REF-OK")
						.with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.bankReference").value("REF-OK"))
				.andExpect(jsonPath("$.status").value("SUCCESS"));

		mockMvc.perform(get("/api/mock-bank/transactions/{ref}", "UNKNOWN")
						.with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))))
				.andExpect(status().isNotFound());
	}
}
