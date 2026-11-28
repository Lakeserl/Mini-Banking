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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import com.lakeserl.mini_banking.deposit.domain.exceptions.DuplicateBankReferenceException;
import com.lakeserl.mini_banking.mockbank.MockBankController;
import com.lakeserl.mini_banking.mockbank.MockBankGateway;
import com.lakeserl.mini_banking.mockbank.MockBankGateway.BankTransaction;
import com.lakeserl.mini_banking.shared.api.ApiExceptionHandler;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.domain.TransactionLimitExceededException;
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

		UUID leaseToken = UUID.randomUUID();
		when(idempotencyService.begin(eq(attackerId), eq("key-3"), anyString())).thenReturn(new Decision.Acquired(leaseToken));
		when(walletRepository.load(walletId)).thenReturn(Optional.of(wallet));

		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.with(jwt().jwt(j -> j.subject(attackerId.toString())))
						.header("Idempotency-Key", "key-3")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":100000,\"currency\":\"VND\",\"bankReference\":\"REF-1\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("FORBIDDEN"));

		verify(depositCommandHandler, never()).deposit(any(), any(), any(), any());
		verify(idempotencyService).release(attackerId, "key-3", leaseToken);
		verify(idempotencyService, never()).complete(any(), any(), any(), anyInt(), any());
	}

	@Test
	void depositByOwnerCompletesAndStoresIdempotencyResult() throws Exception {
		UUID ownerId = UUID.randomUUID();
		UUID walletId = Wallet.calculateWalletId(ownerId, Currency.VND);
		Wallet wallet = Wallet.open(walletId, ownerId, Currency.VND);
		UUID leaseToken = UUID.randomUUID();

		when(idempotencyService.begin(eq(ownerId), eq("key-4"), anyString())).thenReturn(new Decision.Acquired(leaseToken));
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

		verify(idempotencyService).complete(eq(ownerId), eq("key-4"), eq(leaseToken), eq(200), anyString());
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"REF-1\\u200B",
			"\\uFEFFREF-1",
			"REF\\u202E1",
			"REF\\u00851",
			" REF-1",
			"REF-1 ",
			"REF-1\\n"
	})
	void depositRejectsDisallowedBankReferenceWith400ValidationFailed(String jsonEscapedRef) throws Exception {
		UUID ownerId = UUID.randomUUID();
		UUID walletId = Wallet.calculateWalletId(ownerId, Currency.VND);
		when(idempotencyService.begin(eq(ownerId), eq("key-bad-ref"), anyString()))
				.thenReturn(new Decision.Acquired(UUID.randomUUID()));

		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.with(jwt().jwt(j -> j.subject(ownerId.toString())))
						.header("Idempotency-Key", "key-bad-ref")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":100000,\"currency\":\"VND\",\"bankReference\":\"" + jsonEscapedRef + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

		verify(depositCommandHandler, never()).deposit(any(), any(), any(), any());
	}

	@Test
	void depositRejectsBankReferenceOver100CharsWith400ValidationFailed() throws Exception {
		UUID ownerId = UUID.randomUUID();
		UUID walletId = Wallet.calculateWalletId(ownerId, Currency.VND);
		when(idempotencyService.begin(eq(ownerId), eq("key-101"), anyString()))
				.thenReturn(new Decision.Acquired(UUID.randomUUID()));
		String ref101 = "R".repeat(101);

		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.with(jwt().jwt(j -> j.subject(ownerId.toString())))
						.header("Idempotency-Key", "key-101")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":100000,\"currency\":\"VND\",\"bankReference\":\"" + ref101 + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

		verify(depositCommandHandler, never()).deposit(any(), any(), any(), any());
	}

	@Test
	void depositWithDuplicateBankReferenceReturns409WithNeutralMessage() throws Exception {
		UUID ownerId = UUID.randomUUID();
		UUID walletId = Wallet.calculateWalletId(ownerId, Currency.VND);
		Wallet wallet = Wallet.open(walletId, ownerId, Currency.VND);
		UUID leaseToken = UUID.randomUUID();

		when(idempotencyService.begin(eq(ownerId), eq("key-dup-ref"), anyString()))
				.thenReturn(new Decision.Acquired(leaseToken));
		when(walletRepository.load(walletId)).thenReturn(Optional.of(wallet));
		when(depositCommandHandler.deposit(any(), eq(walletId), eq(Money.vnd(100_000)), eq("REF-DUP-1")))
				.thenThrow(new DuplicateBankReferenceException("REF-DUP-1"));

		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.with(jwt().jwt(j -> j.subject(ownerId.toString())))
						.header("Idempotency-Key", "key-dup-ref")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":100000,\"currency\":\"VND\",\"bankReference\":\"REF-DUP-1\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("DUPLICATE_BANK_REFERENCE"))
				.andExpect(jsonPath("$.message").value("Bank reference has already been used"));

		verify(idempotencyService).release(ownerId, "key-dup-ref", leaseToken);
		verify(idempotencyService, never()).complete(any(), any(), any(), anyInt(), any());
	}

	@Test
	void withdrawByOwnerSucceeds() throws Exception {
		UUID ownerId = UUID.randomUUID();
		UUID walletId = Wallet.calculateWalletId(ownerId, Currency.VND);
		Wallet wallet = Wallet.open(walletId, ownerId, Currency.VND);

		when(idempotencyService.begin(eq(ownerId), eq("w-1"), anyString()))
				.thenReturn(new Decision.Acquired(UUID.randomUUID()));
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
	void withdrawReturningPendingReturns202AcceptedAndMarksIdempotencyPending() throws Exception {
		UUID ownerId = UUID.randomUUID();
		UUID walletId = Wallet.calculateWalletId(ownerId, Currency.VND);
		Wallet wallet = Wallet.open(walletId, ownerId, Currency.VND);
		UUID leaseToken = UUID.randomUUID();

		when(idempotencyService.begin(eq(ownerId), eq("w-pending"), anyString()))
				.thenReturn(new Decision.Acquired(leaseToken));
		when(walletRepository.load(walletId)).thenReturn(Optional.of(wallet));
		when(withdrawalCommandHandler.withdraw(any(), eq(walletId), eq(Money.vnd(50_000)), eq("970436-111")))
				.thenReturn(WithdrawalStatus.PENDING);

		mockMvc.perform(post("/api/wallets/{id}/withdraw", walletId)
						.with(jwt().jwt(j -> j.subject(ownerId.toString())))
						.header("Idempotency-Key", "w-pending")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":50000,\"currency\":\"VND\",\"bankAccount\":\"970436-111\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("PENDING"));

		verify(idempotencyService).markPending(ownerId, "w-pending", leaseToken);
		verify(idempotencyService, never()).complete(any(), any(), any(), anyInt(), any());
	}

	@Test
	void withdrawByNonOwnerAndNonOwnerAdminReturns403AndReleasesIdempotencyLock() throws Exception {
		UUID ownerId = UUID.randomUUID();
		UUID attackerId = UUID.randomUUID();
		UUID adminId = UUID.randomUUID();
		UUID walletId = Wallet.calculateWalletId(ownerId, Currency.VND);
		Wallet wallet = Wallet.open(walletId, ownerId, Currency.VND);
		UUID attackerLease = UUID.randomUUID();
		UUID adminLease = UUID.randomUUID();

		when(walletRepository.load(walletId)).thenReturn(Optional.of(wallet));
		when(idempotencyService.begin(eq(attackerId), eq("wd-atk"), anyString()))
				.thenReturn(new Decision.Acquired(attackerLease));
		when(idempotencyService.begin(eq(adminId), eq("wd-admin"), anyString()))
				.thenReturn(new Decision.Acquired(adminLease));

		mockMvc.perform(post("/api/wallets/{id}/withdraw", walletId)
						.with(jwt().jwt(j -> j.subject(attackerId.toString())))
						.header("Idempotency-Key", "wd-atk")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":50000,\"currency\":\"VND\",\"bankAccount\":\"970436-111\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("FORBIDDEN"));
		verify(idempotencyService).release(attackerId, "wd-atk", attackerLease);

		mockMvc.perform(post("/api/wallets/{id}/withdraw", walletId)
						.with(jwt().jwt(j -> j.subject(adminId.toString()).claim("roles", List.of("ADMIN")))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
						.header("Idempotency-Key", "wd-admin")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":50000,\"currency\":\"VND\",\"bankAccount\":\"970436-111\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("FORBIDDEN"));
		verify(idempotencyService).release(adminId, "wd-admin", adminLease);

		verify(withdrawalCommandHandler, never()).withdraw(any(), any(), any(), any());
		verify(idempotencyService, never()).complete(any(), any(), any(), anyInt(), any());
	}

	@Test
	void depositAndWithdrawExceedingSingleLimitReturn422AndReleaseIdempotencyKey() throws Exception {
		UUID ownerId = UUID.randomUUID();
		UUID walletId = Wallet.calculateWalletId(ownerId, Currency.VND);
		Wallet wallet = Wallet.open(walletId, ownerId, Currency.VND);
		UUID depLease = UUID.randomUUID();
		UUID wdLease = UUID.randomUUID();

		when(walletRepository.load(walletId)).thenReturn(Optional.of(wallet));
		when(idempotencyService.begin(eq(ownerId), eq("dep-limit"), anyString())).thenReturn(new Decision.Acquired(depLease));
		when(depositCommandHandler.deposit(any(), eq(walletId), eq(Money.vnd(50_000_001L)), eq("REF-LIMIT")))
				.thenThrow(new TransactionLimitExceededException("Single deposit limit exceeded"));

		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.with(jwt().jwt(j -> j.subject(ownerId.toString())))
						.header("Idempotency-Key", "dep-limit")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":50000001,\"currency\":\"VND\",\"bankReference\":\"REF-LIMIT\"}"))
				.andExpect(status().is(422))
				.andExpect(jsonPath("$.error").value("TRANSACTION_LIMIT_EXCEEDED"));
		verify(idempotencyService).release(ownerId, "dep-limit", depLease);

		when(idempotencyService.begin(eq(ownerId), eq("wd-limit"), anyString())).thenReturn(new Decision.Acquired(wdLease));
		when(withdrawalCommandHandler.withdraw(any(), eq(walletId), eq(Money.vnd(50_000_001L)), eq("970436-111")))
				.thenThrow(new TransactionLimitExceededException("Single withdrawal limit exceeded"));

		mockMvc.perform(post("/api/wallets/{id}/withdraw", walletId)
						.with(jwt().jwt(j -> j.subject(ownerId.toString())))
						.header("Idempotency-Key", "wd-limit")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":50000001,\"currency\":\"VND\",\"bankAccount\":\"970436-111\"}"))
				.andExpect(status().is(422))
				.andExpect(jsonPath("$.error").value("TRANSACTION_LIMIT_EXCEEDED"));
		verify(idempotencyService).release(ownerId, "wd-limit", wdLease);
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
				.andExpect(status().isForbidden());

		mockMvc.perform(get("/api/mock-bank/transactions/{ref}", "REF-OK")
						.with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("roles", List.of("ADMIN")))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.bankReference").value("REF-OK"))
				.andExpect(jsonPath("$.status").value("SUCCESS"));

		mockMvc.perform(get("/api/mock-bank/transactions/{ref}", "UNKNOWN")
						.with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("roles", List.of("ADMIN")))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isNotFound());
	}
}
