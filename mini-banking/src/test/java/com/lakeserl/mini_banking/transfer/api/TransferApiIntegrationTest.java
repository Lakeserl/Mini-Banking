package com.lakeserl.mini_banking.transfer.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.lakeserl.mini_banking.PostgresContainerInitializer;
import com.lakeserl.mini_banking.mockbank.InMemoryMockBankGateway;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
		"outbox.relay.enabled=false",
		"transfer.recovery.enabled=false",
		"ledger.consumer.enabled=false",
		"query.consumer.enabled=false",
		"reconciliation.internal.enabled=false",
		"reconciliation.bank.enabled=false",
		"security.jwt.secret=" + TransferApiIntegrationTest.TEST_JWT_SECRET })
@AutoConfigureMockMvc
@ContextConfiguration(initializers = PostgresContainerInitializer.class)
class TransferApiIntegrationTest {

	static final String TEST_JWT_SECRET = "integration-test-jwt-secret-key-at-least-32-bytes";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	WalletRepository walletRepository;

	@Autowired
	InMemoryMockBankGateway mockBank;

	private final JsonMapper mapper = JsonMapper.builder().build();

	@BeforeEach
	void resetBank() {
		mockBank.reset();
	}

	private String bearerToken(UUID userId, String... roles) throws Exception {
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
				.subject(userId.toString())
				.issueTime(Date.from(Instant.now()))
				.expirationTime(Date.from(Instant.now().plusSeconds(300)));
		if (roles.length > 0) {
			claims.claim("roles", List.of(roles));
		}
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
		jwt.sign(new MACSigner(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
		return "Bearer " + jwt.serialize();
	}

	private UUID openWallet(String token) throws Exception {
		MvcResult res = mockMvc.perform(post("/api/wallets")
						.header(HttpHeaders.AUTHORIZATION, token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currency\":\"VND\"}"))
				.andExpect(status().isCreated())
				.andReturn();
		return UUID.fromString(
				mapper.readValue(res.getResponse().getContentAsString(), JsonNode.class).get("walletId").asString());
	}

	@Test
	void endToEndTransferIdempotencyOwnershipAndRefundFlow() throws Exception {
		UUID aliceId = UUID.randomUUID();
		UUID bobId = UUID.randomUUID();
		UUID malloryId = UUID.randomUUID();

		String aliceToken = bearerToken(aliceId);
		String bobToken = bearerToken(bobId);
		String malloryToken = bearerToken(malloryId);

		// 1. Open Alice & Bob wallets and deposit 500,000 VND into Alice's wallet
		UUID aliceWalletId = openWallet(aliceToken);
		UUID bobWalletId = openWallet(bobToken);

		mockMvc.perform(post("/api/wallets/{id}/deposit", aliceWalletId)
						.header(HttpHeaders.AUTHORIZATION, aliceToken)
						.header("Idempotency-Key", "alice-dep-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":500000,\"currency\":\"VND\",\"bankReference\":\"REF-ALICE-1\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"));

		// 2. Mallory cannot transfer from Alice's wallet -> 403
		String transferPayload = "{\"sourceWalletId\":\"" + aliceWalletId + "\",\"destinationWalletId\":\""
				+ bobWalletId + "\",\"amount\":200000,\"currency\":\"VND\"}";
		mockMvc.perform(post("/api/transfers")
						.header(HttpHeaders.AUTHORIZATION, malloryToken)
						.header("Idempotency-Key", "mallory-tx-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content(transferPayload))
				.andExpect(status().isForbidden());

		// 3. Alice transfers 200,000 VND to Bob with Idempotency-Key; replay does not double-transfer
		MvcResult txRes = mockMvc.perform(post("/api/transfers")
						.header(HttpHeaders.AUTHORIZATION, aliceToken)
						.header("Idempotency-Key", "alice-tx-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content(transferPayload))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.type").value("STANDARD"))
				.andReturn();
		UUID transferId = UUID.fromString(
				mapper.readValue(txRes.getResponse().getContentAsString(), JsonNode.class).get("transferId").asString());

		mockMvc.perform(post("/api/transfers")
						.header(HttpHeaders.AUTHORIZATION, aliceToken)
						.header("Idempotency-Key", "alice-tx-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content(transferPayload))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.transferId").value(transferId.toString()))
				.andExpect(jsonPath("$.status").value("COMPLETED"));

		assertThat(walletRepository.load(aliceWalletId)).get().extracting(w -> w.balance())
				.isEqualTo(Money.vnd(300_000));
		assertThat(walletRepository.load(bobWalletId)).get().extracting(w -> w.balance())
				.isEqualTo(Money.vnd(200_000));

		// 4. Same Idempotency-Key with mismatched payload -> 422
		mockMvc.perform(post("/api/transfers")
						.header(HttpHeaders.AUTHORIZATION, aliceToken)
						.header("Idempotency-Key", "alice-tx-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sourceWalletId\":\"" + aliceWalletId + "\",\"destinationWalletId\":\""
								+ bobWalletId + "\",\"amount\":999000,\"currency\":\"VND\"}"))
				.andExpect(status().is(422))
				.andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_CONFLICT"));

		// 5. GET /api/transfers/{id} succeeds for Alice and Bob, returns 403 for Mallory
		mockMvc.perform(get("/api/transfers/{id}", transferId)
						.header(HttpHeaders.AUTHORIZATION, aliceToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.amount").value(200_000));

		mockMvc.perform(get("/api/transfers/{id}", transferId)
						.header(HttpHeaders.AUTHORIZATION, bobToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.transferId").value(transferId.toString()));

		mockMvc.perform(get("/api/transfers/{id}", transferId)
						.header(HttpHeaders.AUTHORIZATION, malloryToken))
				.andExpect(status().isForbidden());

		// 6. Original sender (Alice) cannot trigger refund -> 403; Receiver (Bob) refunds -> 200 COMPLETED
		mockMvc.perform(post("/api/transfers/{id}/refund", transferId)
						.header(HttpHeaders.AUTHORIZATION, aliceToken)
						.header("Idempotency-Key", "alice-refund-attempt"))
				.andExpect(status().isForbidden());

		MvcResult refundRes = mockMvc.perform(post("/api/transfers/{id}/refund", transferId)
						.header(HttpHeaders.AUTHORIZATION, bobToken)
						.header("Idempotency-Key", "bob-refund-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.type").value("REFUND"))
				.andReturn();
		UUID refundTransferId = UUID.fromString(
				mapper.readValue(refundRes.getResponse().getContentAsString(), JsonNode.class).get("transferId")
						.asString());

		assertThat(walletRepository.load(aliceWalletId)).get().extracting(w -> w.balance())
				.isEqualTo(Money.vnd(500_000));
		assertThat(walletRepository.load(bobWalletId)).get().extracting(w -> w.balance())
				.isEqualTo(Money.vnd(0));

		// 7. GET refund transfer shows originalTransferId link
		mockMvc.perform(get("/api/transfers/{id}", refundTransferId)
						.header(HttpHeaders.AUTHORIZATION, bobToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.type").value("REFUND"))
				.andExpect(jsonPath("$.originalTransferId").value(transferId.toString()));

		// 8. Second refund with a different Idempotency-Key is rejected with 409 INVALID_TRANSFER_STATE
		mockMvc.perform(post("/api/transfers/{id}/refund", transferId)
						.header(HttpHeaders.AUTHORIZATION, bobToken)
						.header("Idempotency-Key", "bob-refund-duplicate"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("INVALID_TRANSFER_STATE"));
	}
}
