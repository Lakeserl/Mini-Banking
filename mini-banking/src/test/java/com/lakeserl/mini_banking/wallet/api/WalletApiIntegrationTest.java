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
import com.lakeserl.mini_banking.mockbank.MockBankGateway.VerificationStatus;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.wallet.domain.WalletStatus;
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

	private UUID openWallet(String token) throws Exception {
		MvcResult openRes = mockMvc.perform(post("/api/wallets")
						.header(HttpHeaders.AUTHORIZATION, token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currency\":\"VND\"}"))
				.andExpect(status().isCreated())
				.andReturn();
		return UUID.fromString(
				mapper.readValue(openRes.getResponse().getContentAsString(), JsonNode.class).get("walletId").asString());
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
		UUID walletId = openWallet(ownerToken);

		// 2. Another user cannot deposit into owner's wallet
		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.header(HttpHeaders.AUTHORIZATION, otherToken)
						.header("Idempotency-Key", "atk-" + UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":500000,\"currency\":\"VND\",\"bankReference\":\"REF-ATK-" + UUID.randomUUID() + "\"}"))
				.andExpect(status().isForbidden());

		// 3. Owner deposits with Idempotency-Key; duplicate request does not double-credit
		String depositRef = "REF-E2E-" + UUID.randomUUID();
		String depositKey = "dep-e2e-" + UUID.randomUUID();
		String depositPayload = "{\"amount\":500000,\"currency\":\"VND\",\"bankReference\":\"" + depositRef + "\"}";
		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", depositKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content(depositPayload))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"));

		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", depositKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content(depositPayload))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"));

		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(500_000));

		// 4. Same Idempotency-Key with different payload -> 422
		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", depositKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":999000,\"currency\":\"VND\",\"bankReference\":\"" + depositRef + "\"}"))
				.andExpect(status().is(422));

		// 5. Mock bank verification endpoint requires ROLE_ADMIN and returns recorded deposit
		mockMvc.perform(get("/api/mock-bank/transactions/{ref}", depositRef)
						.header(HttpHeaders.AUTHORIZATION, ownerToken))
				.andExpect(status().isForbidden());

		mockMvc.perform(get("/api/mock-bank/transactions/{ref}", depositRef)
						.header(HttpHeaders.AUTHORIZATION, adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SUCCESS"));

		// 6. Owner withdraws; duplicate HTTP request does not double-debit
		String withdrawKey = "wd-e2e-" + UUID.randomUUID();
		String withdrawPayload = "{\"amount\":200000,\"currency\":\"VND\",\"bankAccount\":\"970436-999\"}";
		mockMvc.perform(post("/api/wallets/{id}/withdraw", walletId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", withdrawKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content(withdrawPayload))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"));

		mockMvc.perform(post("/api/wallets/{id}/withdraw", walletId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", withdrawKey)
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
						.header("Idempotency-Key", "wd-e2e-after-lock-" + UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":10000,\"currency\":\"VND\",\"bankAccount\":\"970436-999\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("WALLET_LOCKED"));
	}

	@Test
	void depositValidationRejectsInvisibleFormatEdgeWhitespaceAndOver100Chars() throws Exception {
		UUID ownerId = UUID.randomUUID();
		String ownerToken = bearerToken(ownerId);
		UUID walletId = openWallet(ownerToken);

		for (String badRef : List.of("REF-1\\u200B", "\\uFEFFREF-1", " REF-1", "REF-1 ", "R".repeat(101))) {
			mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
							.header(HttpHeaders.AUTHORIZATION, ownerToken)
							.header("Idempotency-Key", "bad-ref-" + UUID.randomUUID())
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"amount\":100000,\"currency\":\"VND\",\"bankReference\":\"" + badRef + "\"}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
		}
	}

	@Test
	void depositRejectsPreLockedWalletAndSettlesMidFlightLockWhileKeepingWalletLocked() throws Exception {
		UUID ownerId = UUID.randomUUID();
		UUID otherOwnerId = UUID.randomUUID();
		UUID adminId = UUID.randomUUID();
		String ownerToken = bearerToken(ownerId);
		String otherToken = bearerToken(otherOwnerId);
		String adminToken = bearerToken(adminId, "ADMIN");

		UUID walletId = openWallet(ownerToken);
		UUID destWalletId = openWallet(otherToken);

		// 1. Start a deposit that times out -> 202 PENDING
		String midLockRef = "REF-MIDLOCK-" + UUID.randomUUID();
		String midLockKey = "dep-midlock-" + UUID.randomUUID();
		String midLockBody = "{\"amount\":400000,\"currency\":\"VND\",\"bankReference\":\"" + midLockRef + "\"}";
		mockBank.presetDeposit(midLockRef, VerificationStatus.TIMEOUT);

		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", midLockKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content(midLockBody))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("PENDING"));

		// 2. Admin locks wallet while deposit is PENDING
		mockMvc.perform(post("/api/wallets/{id}/lock", walletId)
						.header(HttpHeaders.AUTHORIZATION, adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"aml hold\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("LOCKED"));

		// 3. Brand-new deposit on already-LOCKED wallet -> 409 WALLET_LOCKED
		String blockedRef = "REF-NEWLOCK-" + UUID.randomUUID();
		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", "dep-newlock-" + UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":100000,\"currency\":\"VND\",\"bankReference\":\"" + blockedRef + "\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("WALLET_LOCKED"));

		// 4. Bank now reports SUCCESS for the mid-flight deposit -> retry settles to 200 CONFIRMED
		mockBank.presetDeposit(midLockRef, VerificationStatus.SUCCESS);
		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", midLockKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content(midLockBody))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"));

		// 5. Wallet received funds, remains LOCKED, and blocks outbound withdraw/transfer
		var wallet = walletRepository.load(walletId).orElseThrow();
		assertThat(wallet.balance()).isEqualTo(Money.vnd(400_000));
		assertThat(wallet.status()).isEqualTo(WalletStatus.LOCKED);

		mockMvc.perform(post("/api/wallets/{id}/withdraw", walletId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", "wd-still-locked-" + UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":50000,\"currency\":\"VND\",\"bankAccount\":\"970436-111\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("WALLET_LOCKED"));

		mockMvc.perform(post("/api/transfers")
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", "tx-still-locked-" + UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sourceWalletId\":\"" + walletId + "\",\"destinationWalletId\":\""
								+ destWalletId + "\",\"amount\":50000,\"currency\":\"VND\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("WALLET_LOCKED"));
	}

	@Test
	void depositEnforcesCaseInsensitiveAndPostFailureBankReferenceUniqueness() throws Exception {
		UUID ownerId = UUID.randomUUID();
		String ownerToken = bearerToken(ownerId);
		UUID walletId = openWallet(ownerToken);

		String okRef = "REF-CASE-" + UUID.randomUUID();
		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", "dep-case-1-" + UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":150000,\"currency\":\"VND\",\"bankReference\":\"" + okRef + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"));

		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", "dep-case-2-" + UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":150000,\"currency\":\"VND\",\"bankReference\":\"" + okRef.toLowerCase() + "\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("DUPLICATE_BANK_REFERENCE"))
				.andExpect(jsonPath("$.message").value("Bank reference has already been used"));

		String failedRef = "REF-DEAD-" + UUID.randomUUID();
		mockBank.presetDeposit(failedRef, VerificationStatus.REJECTED);
		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", "dep-dead-1-" + UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":100000,\"currency\":\"VND\",\"bankReference\":\"" + failedRef + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"));

		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", "dep-dead-2-" + UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"amount\":100000,\"currency\":\"VND\",\"bankReference\":\"" + failedRef.toLowerCase() + "\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.error").value("DUPLICATE_BANK_REFERENCE"));
	}

	@Test
	void deposit202PendingSupportsCrossEndpoint422AndRetryConvergenceTo200Replay() throws Exception {
		UUID ownerId = UUID.randomUUID();
		UUID otherId = UUID.randomUUID();
		String ownerToken = bearerToken(ownerId);
		String otherToken = bearerToken(otherId);
		UUID walletId = openWallet(ownerToken);
		UUID otherWalletId = openWallet(otherToken);

		String ref = "REF-PEND-" + UUID.randomUUID();
		String idemKey = "dep-pend-" + UUID.randomUUID();
		String depositBody = "{\"amount\":250000,\"currency\":\"VND\",\"bankReference\":\"" + ref + "\"}";
		mockBank.presetDeposit(ref, VerificationStatus.TIMEOUT);

		// 1. Initial attempt -> 202 PENDING (idempotency stored as PENDING)
		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", idemKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content(depositBody))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("PENDING"));

		// 2. Reusing same Idempotency-Key on /api/transfers while PENDING -> 422 IDEMPOTENCY_KEY_CONFLICT
		mockMvc.perform(post("/api/transfers")
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", idemKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"sourceWalletId\":\"" + walletId + "\",\"destinationWalletId\":\""
								+ otherWalletId + "\",\"amount\":250000,\"currency\":\"VND\"}"))
				.andExpect(status().is(422))
				.andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_CONFLICT"));

		// 3. Retry with same key after bank flips to SUCCESS -> 200 CONFIRMED
		mockBank.presetDeposit(ref, VerificationStatus.SUCCESS);
		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", idemKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content(depositBody))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"));

		// 4. Subsequent replay returns cached 200 without double credit
		mockMvc.perform(post("/api/wallets/{id}/deposit", walletId)
						.header(HttpHeaders.AUTHORIZATION, ownerToken)
						.header("Idempotency-Key", idemKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content(depositBody))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CONFIRMED"));

		assertThat(walletRepository.load(walletId)).get().extracting(w -> w.balance()).isEqualTo(Money.vnd(250_000));
	}
}
