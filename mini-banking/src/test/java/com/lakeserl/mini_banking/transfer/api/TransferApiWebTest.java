package com.lakeserl.mini_banking.transfer.api;

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

import com.lakeserl.mini_banking.shared.api.ApiExceptionHandler;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.idempotency.IdempotencyService;
import com.lakeserl.mini_banking.shared.idempotency.IdempotencyService.Decision;
import com.lakeserl.mini_banking.shared.security.SecurityConfig;
import com.lakeserl.mini_banking.transfer.domain.Transfer;
import com.lakeserl.mini_banking.transfer.domain.TransferType;
import com.lakeserl.mini_banking.transfer.saga.TransferSagaOrchestrator;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;

@WebMvcTest(controllers = TransferController.class)
@Import({ SecurityConfig.class, ApiExceptionHandler.class })
class TransferApiWebTest {

	@Autowired
	MockMvc mockMvc;

	@MockitoBean
	TransferSagaOrchestrator orchestrator;

	@MockitoBean
	WalletRepository walletRepository;

	@MockitoBean
	IdempotencyService idempotencyService;

	@MockitoBean
	JwtDecoder jwtDecoder;

	@Test
	void unauthenticatedRequestReturns401() throws Exception {
		mockMvc.perform(post("/api/transfers"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void transferRequiresIdempotencyKeyHeader() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID sourceId = UUID.randomUUID();
		UUID destId = UUID.randomUUID();

		mockMvc.perform(post("/api/transfers")
						.with(jwt().jwt(j -> j.subject(userId.toString())))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sourceWalletId\":\"" + sourceId + "\",\"destinationWalletId\":\"" + destId
								+ "\",\"amount\":100000,\"currency\":\"VND\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("INVALID_IDEMPOTENCY_KEY"));
	}

	@Test
	void transferWithMissingSourceOrDestinationWalletIdReturns400ValidationFailed() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID destId = UUID.randomUUID();
		when(idempotencyService.begin(eq(userId), eq("t-val-1"), anyString())).thenReturn(new Decision.Acquired());

		mockMvc.perform(post("/api/transfers")
						.with(jwt().jwt(j -> j.subject(userId.toString())))
						.header("Idempotency-Key", "t-val-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"destinationWalletId\":\"" + destId + "\",\"amount\":100000,\"currency\":\"VND\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

		UUID sourceId = UUID.randomUUID();
		when(idempotencyService.begin(eq(userId), eq("t-val-2"), anyString())).thenReturn(new Decision.Acquired());
		mockMvc.perform(post("/api/transfers")
						.with(jwt().jwt(j -> j.subject(userId.toString())))
						.header("Idempotency-Key", "t-val-2")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sourceWalletId\":\"" + sourceId + "\",\"amount\":100000,\"currency\":\"VND\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

		verify(orchestrator, never()).transfer(any(), any(), any(), any());
	}

	@Test
	void transferDerivatesDeterministicIdAndRefundReturns202WhenNonTerminal() throws Exception {
		UUID ownerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		UUID receiverId = UUID.fromString("22222222-2222-2222-2222-222222222222");
		UUID sourceId = Wallet.calculateWalletId(ownerId, Currency.VND);
		UUID destId = Wallet.calculateWalletId(receiverId, Currency.VND);
		Wallet sourceWallet = Wallet.open(sourceId, ownerId, Currency.VND);
		Wallet destWallet = Wallet.open(destId, receiverId, Currency.VND);

		UUID expectedTransferId = com.lakeserl.mini_banking.shared.domain.DeterministicIds.v5(
				UUID.fromString("2b8e5c4a-9f13-4d72-8a61-3e7c9f4b2d15"),
				ownerId + "|det-tx-key");
		Transfer completed = Transfer.initiate(expectedTransferId, sourceId, destId, Money.vnd(100_000),
				TransferType.STANDARD, null);
		completed.markDebitCompleted();
		completed.markCreditAndComplete();

		when(idempotencyService.begin(eq(ownerId), eq("det-tx-key"), anyString())).thenReturn(new Decision.Acquired());
		when(walletRepository.load(sourceId)).thenReturn(Optional.of(sourceWallet));
		when(orchestrator.transfer(eq(expectedTransferId), eq(sourceId), eq(destId), eq(Money.vnd(100_000))))
				.thenReturn(completed);

		mockMvc.perform(post("/api/transfers")
						.with(jwt().jwt(j -> j.subject(ownerId.toString())))
						.header("Idempotency-Key", "det-tx-key")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sourceWalletId\":\"" + sourceId + "\",\"destinationWalletId\":\"" + destId
								+ "\",\"amount\":100000,\"currency\":\"VND\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.transferId").value(expectedTransferId.toString()))
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		UUID expectedRefundId = com.lakeserl.mini_banking.shared.domain.DeterministicIds.v5(
				UUID.fromString("6c1d9e3b-5a84-4f29-b703-8d2f4a6c9e51"),
				receiverId + "|" + expectedTransferId + "|det-rf-key");
		Transfer nonTerminalRefund = Transfer.initiate(expectedRefundId, destId, sourceId, Money.vnd(100_000),
				TransferType.REFUND, expectedTransferId);
		nonTerminalRefund.markDebitCompleted();

		when(idempotencyService.begin(eq(receiverId), eq("det-rf-key"), anyString())).thenReturn(new Decision.Acquired());
		when(orchestrator.findTransfer(expectedTransferId)).thenReturn(Optional.of(completed));
		when(walletRepository.load(destId)).thenReturn(Optional.of(destWallet));
		when(orchestrator.refund(eq(expectedRefundId), eq(expectedTransferId))).thenReturn(nonTerminalRefund);

		mockMvc.perform(post("/api/transfers/{id}/refund", expectedTransferId)
						.with(jwt().jwt(j -> j.subject(receiverId.toString())))
						.header("Idempotency-Key", "det-rf-key"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.transferId").value(expectedRefundId.toString()))
				.andExpect(jsonPath("$.status").value("DEBITED"))
				.andExpect(jsonPath("$.type").value("REFUND"));
	}

	@Test
	void transferByNonOwnerReturns403AndReleasesIdempotencyLock() throws Exception {
		UUID ownerId = UUID.randomUUID();
		UUID attackerId = UUID.randomUUID();
		UUID sourceId = Wallet.calculateWalletId(ownerId, Currency.VND);
		UUID destId = UUID.randomUUID();
		Wallet sourceWallet = Wallet.open(sourceId, ownerId, Currency.VND);

		when(idempotencyService.begin(eq(attackerId), eq("t-atk-1"), anyString())).thenReturn(new Decision.Acquired());
		when(walletRepository.load(sourceId)).thenReturn(Optional.of(sourceWallet));

		mockMvc.perform(post("/api/transfers")
						.with(jwt().jwt(j -> j.subject(attackerId.toString())))
						.header("Idempotency-Key", "t-atk-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sourceWalletId\":\"" + sourceId + "\",\"destinationWalletId\":\"" + destId
								+ "\",\"amount\":100000,\"currency\":\"VND\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("FORBIDDEN"));

		verify(orchestrator, never()).transfer(any(), any(), any(), any());
		verify(idempotencyService).release(attackerId, "t-atk-1");
		verify(idempotencyService, never()).complete(any(), any(), anyInt(), any());
	}

	@Test
	void transferByOwnerReturns200WhenCompletedAnd202WhenNonTerminal() throws Exception {
		UUID ownerId = UUID.randomUUID();
		UUID sourceId = Wallet.calculateWalletId(ownerId, Currency.VND);
		UUID destId = UUID.randomUUID();
		Wallet sourceWallet = Wallet.open(sourceId, ownerId, Currency.VND);

		when(walletRepository.load(sourceId)).thenReturn(Optional.of(sourceWallet));

		// Terminal COMPLETED -> 200 OK
		when(idempotencyService.begin(eq(ownerId), eq("t-ok-1"), anyString())).thenReturn(new Decision.Acquired());
		when(orchestrator.transfer(any(), eq(sourceId), eq(destId), eq(Money.vnd(100_000))))
				.thenAnswer(inv -> {
					UUID transferId = inv.getArgument(0);
					Transfer t = Transfer.initiate(transferId, sourceId, destId, Money.vnd(100_000),
							TransferType.STANDARD, null);
					t.markDebitCompleted();
					t.markCreditAndComplete();
					return t;
				});

		mockMvc.perform(post("/api/transfers")
						.with(jwt().jwt(j -> j.subject(ownerId.toString())))
						.header("Idempotency-Key", "t-ok-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sourceWalletId\":\"" + sourceId + "\",\"destinationWalletId\":\"" + destId
								+ "\",\"amount\":100000,\"currency\":\"VND\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.type").value("STANDARD"));

		// Non-terminal DEBITED -> 202 Accepted
		when(idempotencyService.begin(eq(ownerId), eq("t-acc-1"), anyString())).thenReturn(new Decision.Acquired());
		when(orchestrator.transfer(any(), eq(sourceId), eq(destId), eq(Money.vnd(50_000))))
				.thenAnswer(inv -> {
					UUID transferId = inv.getArgument(0);
					Transfer t = Transfer.initiate(transferId, sourceId, destId, Money.vnd(50_000),
							TransferType.STANDARD, null);
					t.markDebitCompleted();
					return t;
				});

		mockMvc.perform(post("/api/transfers")
						.with(jwt().jwt(j -> j.subject(ownerId.toString())))
						.header("Idempotency-Key", "t-acc-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sourceWalletId\":\"" + sourceId + "\",\"destinationWalletId\":\"" + destId
								+ "\",\"amount\":50000,\"currency\":\"VND\"}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("DEBITED"));
	}

	@Test
	void refundRequiresIdempotencyKeyHeader() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID originalTransferId = UUID.randomUUID();

		mockMvc.perform(post("/api/transfers/{id}/refund", originalTransferId)
						.with(jwt().jwt(j -> j.subject(userId.toString()))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.error").value("INVALID_IDEMPOTENCY_KEY"));
	}

	@Test
	void refundRejectsOriginalSenderAndAllowsReceiverOrAdmin() throws Exception {
		UUID senderId = UUID.randomUUID();
		UUID receiverId = UUID.randomUUID();
		UUID adminId = UUID.randomUUID();
		UUID sourceId = Wallet.calculateWalletId(senderId, Currency.VND);
		UUID destId = Wallet.calculateWalletId(receiverId, Currency.VND);
		Wallet sourceWallet = Wallet.open(sourceId, senderId, Currency.VND);
		Wallet destWallet = Wallet.open(destId, receiverId, Currency.VND);

		UUID originalId = UUID.randomUUID();
		Transfer original = Transfer.initiate(originalId, sourceId, destId, Money.vnd(100_000),
				TransferType.STANDARD, null);
		original.markDebitCompleted();
		original.markCreditAndComplete();

		when(orchestrator.findTransfer(originalId)).thenReturn(Optional.of(original));
		when(walletRepository.load(sourceId)).thenReturn(Optional.of(sourceWallet));
		when(walletRepository.load(destId)).thenReturn(Optional.of(destWallet));

		// Original sender -> 403 Forbidden
		when(idempotencyService.begin(eq(senderId), eq("rf-sender"), anyString())).thenReturn(new Decision.Acquired());
		mockMvc.perform(post("/api/transfers/{id}/refund", originalId)
						.with(jwt().jwt(j -> j.subject(senderId.toString())))
						.header("Idempotency-Key", "rf-sender"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("FORBIDDEN"));
		verify(orchestrator, never()).refund(any(), any());

		// Original receiver -> 200 OK (no request body required)
		when(idempotencyService.begin(eq(receiverId), eq("rf-recv"), anyString())).thenReturn(new Decision.Acquired());
		when(orchestrator.refund(any(), eq(originalId))).thenAnswer(inv -> {
			UUID refundId = inv.getArgument(0);
			Transfer rf = Transfer.initiate(refundId, destId, sourceId, Money.vnd(100_000),
					TransferType.REFUND, originalId);
			rf.markDebitCompleted();
			rf.markCreditAndComplete();
			return rf;
		});

		mockMvc.perform(post("/api/transfers/{id}/refund", originalId)
						.with(jwt().jwt(j -> j.subject(receiverId.toString())))
						.header("Idempotency-Key", "rf-recv"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.type").value("REFUND"));

		// Admin -> 200 OK (short-circuits before loading destination wallet)
		UUID adminOnlyOriginalId = UUID.randomUUID();
		UUID missingDestWalletId = UUID.randomUUID();
		Transfer adminOnlyOriginal = Transfer.initiate(adminOnlyOriginalId, sourceId, missingDestWalletId,
				Money.vnd(100_000), TransferType.STANDARD, null);
		adminOnlyOriginal.markDebitCompleted();
		adminOnlyOriginal.markCreditAndComplete();
		when(orchestrator.findTransfer(adminOnlyOriginalId)).thenReturn(Optional.of(adminOnlyOriginal));
		when(idempotencyService.begin(eq(adminId), eq("rf-admin"), anyString())).thenReturn(new Decision.Acquired());
		when(orchestrator.refund(any(), eq(adminOnlyOriginalId))).thenAnswer(inv -> {
			UUID refundId = inv.getArgument(0);
			Transfer rf = Transfer.initiate(refundId, missingDestWalletId, sourceId, Money.vnd(100_000),
					TransferType.REFUND, adminOnlyOriginalId);
			rf.markDebitCompleted();
			rf.markCreditAndComplete();
			return rf;
		});
		mockMvc.perform(post("/api/transfers/{id}/refund", adminOnlyOriginalId)
						.with(jwt().jwt(j -> j.subject(adminId.toString()).claim("roles", List.of("ADMIN")))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
						.header("Idempotency-Key", "rf-admin"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.type").value("REFUND"));
		verify(walletRepository, never()).load(missingDestWalletId);
	}

	@Test
	void getTransferEnforcesParticipantOrAdminAuthorization() throws Exception {
		UUID senderId = UUID.randomUUID();
		UUID receiverId = UUID.randomUUID();
		UUID outsiderId = UUID.randomUUID();
		UUID adminId = UUID.randomUUID();
		UUID sourceId = Wallet.calculateWalletId(senderId, Currency.VND);
		UUID destId = Wallet.calculateWalletId(receiverId, Currency.VND);
		Wallet sourceWallet = Wallet.open(sourceId, senderId, Currency.VND);
		Wallet destWallet = Wallet.open(destId, receiverId, Currency.VND);

		UUID transferId = UUID.randomUUID();
		Transfer transfer = Transfer.initiate(transferId, sourceId, destId, Money.vnd(125_000),
				TransferType.STANDARD, null);
		transfer.markDebitCompleted();
		transfer.markCreditAndComplete();

		when(orchestrator.findTransfer(transferId)).thenReturn(Optional.of(transfer));
		when(walletRepository.load(sourceId)).thenReturn(Optional.of(sourceWallet));
		when(walletRepository.load(destId)).thenReturn(Optional.of(destWallet));

		// Unrelated user -> 403
		mockMvc.perform(get("/api/transfers/{id}", transferId)
						.with(jwt().jwt(j -> j.subject(outsiderId.toString()))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("FORBIDDEN"));

		// Source wallet owner -> 200
		mockMvc.perform(get("/api/transfers/{id}", transferId)
						.with(jwt().jwt(j -> j.subject(senderId.toString()))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.transferId").value(transferId.toString()))
				.andExpect(jsonPath("$.sourceWalletId").value(sourceId.toString()))
				.andExpect(jsonPath("$.destinationWalletId").value(destId.toString()))
				.andExpect(jsonPath("$.amount").value(125_000))
				.andExpect(jsonPath("$.currency").value("VND"))
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.type").value("STANDARD"));

		// Destination wallet owner -> 200
		mockMvc.perform(get("/api/transfers/{id}", transferId)
						.with(jwt().jwt(j -> j.subject(receiverId.toString()))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.transferId").value(transferId.toString()));

		// Admin -> 200
		mockMvc.perform(get("/api/transfers/{id}", transferId)
						.with(jwt().jwt(j -> j.subject(adminId.toString()).claim("roles", List.of("ADMIN")))
								.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.transferId").value(transferId.toString()));
	}
}
