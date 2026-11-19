package com.lakeserl.mini_banking.wallet.api;

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
import org.springframework.jdbc.core.JdbcTemplate;
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
		"security.jwt.secret=" + WalletApiIntegrationTest.TEST_JWT_SECRET })
@AutoConfigureMockMvc
@ContextConfiguration(initializers = PostgresContainerInitializer.class)
class WalletApiIntegrationTest {

	static final String TEST_JWT_SECRET = "integration-test-jwt-secret-key-at-least-32-bytes";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	WalletRepository walletRepository;

	@Autowired
	InMemoryMockBankGateway mockBank;

	@Autowired
	JdbcTemplate jdbc;

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

	@Test
	void fullPhase1HttpFlowEnforcesAuthOwnershipIdempotencyAndOutboxAtomicity() throws Exception {
		UUID ownerId = UUID.randomUUID();
		UUID otherUserId = UUID.randomUUID();
		UUID adminId = UUID.randomUUID();

		String ownerToken = bearerToken(ownerId);
		String otherToken = bearerToken(otherUserId);
		String adminToken = bearerToken(adminId, "ADMIN");

		// 1. Open wallet with real signed JWT
		MvcResult openRes = mockMvc.perform(post("/api/wallets")
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currency\":\"VND\"}"))
				.andExpect(status().isCreated())
				.andReturn();
		UUID walletId = UUID.fromString(
				mapper.readValue(openRes.getResponse().getContentAsString(), JsonNode.class).get("walletId").asString());

		// 2. Another user cannot deposit into owner's wallet
		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.header(HttpHeaders.AUTHORIZATION, otherToken)
						.header("Idempotency-Key", "atk-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":500000,\"currency\":\"VND\",\"bankReference\":\"REF-ATK\"}"))
				.andExpect(status().isForbidden());

		// 3. Owner deposits with Idempotency-Key; duplicate request does not double-credit
		String depositPayload = "{\"amount\":500000,\"currency\":\"VND\",\"bankReference\":\"REF-E2E-1\"}";
		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", "dep-e2e-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content(depositPayload))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"));

		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", "dep-e2e-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content(depositPayload))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"));

		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(500_000));

		// 4. Same Idempotency-Key with different payload -> 422
		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", "dep-e2e-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":999000,\"currency\":\"VND\",\"bankReference\":\"REF-E2E-1\"}"))
				.andExpect(status().is(422));

		// 5. Mock bank verification endpoint returns recorded deposit
		mockMvc.perform(get("/api/mock-bank/transactions/{ref}", "REF-E2E-1")
						.header(HttpHeaders.AUTHORIZATION, ownerToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SUCCESS"));

		// 6. Owner withdraws; duplicate HTTP request does not double-debit
		String withdrawPayload = "{\"amount\":200000,\"currency\":\"VND\",\"bankAccount\":\"970436-999\"}";
		mockMvc.perform(post("/api/wallets/{id}/withdraw", walletId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", "wd-e2e-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content(withdrawPayload))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"));

		mockMvc.perform(post("/api/wallets/{id}/withdraw", walletId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", "wd-e2e-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content(withdrawPayload))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"));

		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(300_000));

		// 7. Events & outbox rows were written atomically for this wallet (open + credit + debit = 3)
		Integer walletEventCount = jdbc.queryForObject(
				"SELECT count(*) FROM events WHERE aggregate_id = ?", Integer.class, walletId);
		Integer walletOutboxCount = jdbc.queryForObject(
				"SELECT count(*) FROM outbox WHERE aggregate_id = ?", Integer.class, walletId);
		assertThat(walletEventCount).isEqualTo(3);
		assertThat(walletOutboxCount).isEqualTo(3);

		// 8. Admin can lock wallet; subsequent withdraw is rejected with 409 WALLET_LOCKED
		mockMvc.perform(post("/api/wallets/{id}/lock", walletId)
						.header(HttpHeaders.AUTHORIZATION, adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"risk review\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("LOCKED"));

		mockMvc.perform(post("/api/wallets/{id}/withdraw", walletId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", "wd-e2e-after-lock")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":10000,\"currency\":\"VND\",\"bankAccount\":\"970436-999\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("WALLET_LOCKED"));
	}
}
