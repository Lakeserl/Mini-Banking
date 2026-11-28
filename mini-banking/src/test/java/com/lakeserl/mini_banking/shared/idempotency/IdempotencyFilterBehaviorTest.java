package com.lakeserl.mini_banking.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponseWrapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.lakeserl.mini_banking.shared.idempotency.IdempotencyService.Decision;

class IdempotencyFilterBehaviorTest {

	private IdempotencyService idempotencyService;
	private IdempotencyFilter filter;
	private UUID userId;

	@BeforeEach
	void setUp() {
		idempotencyService = mock(IdempotencyService.class);
		filter = new IdempotencyFilter(idempotencyService);
		userId = UUID.randomUUID();
		Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(3600),
				Map.of("alg", "HS256"), Map.of("sub", userId.toString()));
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void rejectsOversizedRequestBodyWith413BeforeCallingIdempotencyService() throws Exception {
		byte[] oversized = new byte[IdempotencyFilter.MAX_BODY_BYTES + 1];
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/transfers");
		request.addHeader(IdempotencyFilter.HEADER, "idem-oversized");
		request.setContent(oversized);
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(413);
		assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
		verify(idempotencyService, never()).begin(any(), anyString(), anyString());
		verify(chain, never()).doFilter(any(), any());
	}

	@Test
	void allowsRequestBodyAtExactLimitAndCompletesTransfer200WithLeaseToken() throws Exception {
		byte[] exactLimit = new byte[IdempotencyFilter.MAX_BODY_BYTES];
		for (int i = 0; i < exactLimit.length; i++) {
			exactLimit[i] = 'a';
		}
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/transfers");
		request.addHeader(IdempotencyFilter.HEADER, "idem-exact-limit");
		request.setContent(exactLimit);
		MockHttpServletResponse response = new MockHttpServletResponse();

		UUID leaseToken = UUID.randomUUID();
		when(idempotencyService.begin(any(), anyString(), anyString())).thenReturn(new Decision.Acquired(leaseToken));
		FilterChain chain = (req, res) -> {
			byte[] read = req.getInputStream().readAllBytes();
			assertThat(read).hasSize(IdempotencyFilter.MAX_BODY_BYTES);
			((MockHttpServletResponse) ((HttpServletResponseWrapper) res).getResponse()).setStatus(200);
			res.getOutputStream().write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
		};

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(200);
		verify(idempotencyService).complete(userId, "idem-exact-limit", leaseToken, 200, "{\"ok\":true}");
		verify(idempotencyService, never()).markPending(any(), anyString(), any());
		verify(idempotencyService, never()).release(any(), anyString(), any());
	}

	@Test
	void deposit202MarksPendingInsteadOfCompleting() throws Exception {
		UUID walletId = UUID.randomUUID();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/wallets/" + walletId + "/deposit");
		request.addHeader(IdempotencyFilter.HEADER, "dep-pending-key");
		request.setContent("{\"amount\":100000}".getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse response = new MockHttpServletResponse();

		UUID leaseToken = UUID.randomUUID();
		when(idempotencyService.begin(any(), anyString(), anyString())).thenReturn(new Decision.Acquired(leaseToken));
		FilterChain chain = (req, res) -> {
			((MockHttpServletResponse) ((HttpServletResponseWrapper) res).getResponse()).setStatus(202);
			res.getOutputStream().write("{\"status\":\"PENDING\"}".getBytes(StandardCharsets.UTF_8));
		};

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(202);
		assertThat(response.getContentAsString()).isEqualTo("{\"status\":\"PENDING\"}");
		verify(idempotencyService).markPending(userId, "dep-pending-key", leaseToken);
		verify(idempotencyService, never()).complete(any(), anyString(), any(), anyInt(), anyString());
		verify(idempotencyService, never()).release(any(), anyString(), any());
	}

	@Test
	void transfer202StillCompletesRatherThanMarkingPending() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/transfers");
		request.addHeader(IdempotencyFilter.HEADER, "tx-202-key");
		request.setContent("{\"amount\":100000}".getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse response = new MockHttpServletResponse();

		UUID leaseToken = UUID.randomUUID();
		when(idempotencyService.begin(any(), anyString(), anyString())).thenReturn(new Decision.Acquired(leaseToken));
		FilterChain chain = (req, res) -> {
			((MockHttpServletResponse) ((HttpServletResponseWrapper) res).getResponse()).setStatus(202);
			res.getOutputStream().write("{\"status\":\"DEBITED\"}".getBytes(StandardCharsets.UTF_8));
		};

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(202);
		verify(idempotencyService).complete(userId, "tx-202-key", leaseToken, 202, "{\"status\":\"DEBITED\"}");
		verify(idempotencyService, never()).markPending(any(), anyString(), any());
	}

	@Test
	void percentEncodedDeposit202MarksPendingAndHashesCanonicalPath() throws Exception {
		UUID walletId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
		byte[] body = "{\"amount\":100000}".getBytes(StandardCharsets.UTF_8);
		String canonicalPath = "/api/wallets/" + walletId + "/deposit";
		String expectedHash = IdempotencyService.sha256("POST", canonicalPath, body);

		MockHttpServletRequest request = new MockHttpServletRequest(
				"POST", "/api/wallets/%3550e8400-e29b-41d4-a716-446655440000/%64eposit");
		request.addHeader(IdempotencyFilter.HEADER, "dep-encoded-key");
		request.setContent(body);
		MockHttpServletResponse response = new MockHttpServletResponse();

		UUID leaseToken = UUID.randomUUID();
		when(idempotencyService.begin(eq(userId), eq("dep-encoded-key"), eq(expectedHash)))
				.thenReturn(new Decision.Acquired(leaseToken));
		FilterChain chain = (req, res) -> {
			((MockHttpServletResponse) ((HttpServletResponseWrapper) res).getResponse()).setStatus(202);
			res.getOutputStream().write("{\"status\":\"PENDING\"}".getBytes(StandardCharsets.UTF_8));
		};

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(202);
		assertThat(response.getContentAsString()).isEqualTo("{\"status\":\"PENDING\"}");
		verify(idempotencyService).begin(userId, "dep-encoded-key", expectedHash);
		verify(idempotencyService).markPending(userId, "dep-encoded-key", leaseToken);
		verify(idempotencyService, never()).complete(any(), anyString(), any(), anyInt(), anyString());
		verify(idempotencyService, never()).release(any(), anyString(), any());
	}

	@Test
	void withdraw202MarksPendingInsteadOfCompleting() throws Exception {
		UUID walletId = UUID.randomUUID();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/wallets/" + walletId + "/withdraw");
		request.addHeader(IdempotencyFilter.HEADER, "wd-pending-key");
		request.setContent("{\"amount\":100000,\"bankAccount\":\"97043612345678\"}".getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse response = new MockHttpServletResponse();

		UUID leaseToken = UUID.randomUUID();
		when(idempotencyService.begin(any(), anyString(), anyString())).thenReturn(new Decision.Acquired(leaseToken));
		FilterChain chain = (req, res) -> {
			((MockHttpServletResponse) ((HttpServletResponseWrapper) res).getResponse()).setStatus(202);
			res.getOutputStream().write("{\"status\":\"PENDING\"}".getBytes(StandardCharsets.UTF_8));
		};

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(202);
		assertThat(response.getContentAsString()).isEqualTo("{\"status\":\"PENDING\"}");
		verify(idempotencyService).markPending(userId, "wd-pending-key", leaseToken);
		verify(idempotencyService, never()).complete(any(), anyString(), any(), anyInt(), anyString());
		verify(idempotencyService, never()).release(any(), anyString(), any());
	}

	@Test
	void percentEncodedWithdraw202MarksPendingAndHashesCanonicalPath() throws Exception {
		UUID walletId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
		byte[] body = "{\"amount\":100000}".getBytes(StandardCharsets.UTF_8);
		String canonicalPath = "/api/wallets/" + walletId + "/withdraw";
		String expectedHash = IdempotencyService.sha256("POST", canonicalPath, body);

		MockHttpServletRequest request = new MockHttpServletRequest(
				"POST", "/api/wallets/%3550e8400-e29b-41d4-a716-446655440000/%77ithdraw");
		request.addHeader(IdempotencyFilter.HEADER, "wd-encoded-key");
		request.setContent(body);
		MockHttpServletResponse response = new MockHttpServletResponse();

		UUID leaseToken = UUID.randomUUID();
		when(idempotencyService.begin(eq(userId), eq("wd-encoded-key"), eq(expectedHash)))
				.thenReturn(new Decision.Acquired(leaseToken));
		FilterChain chain = (req, res) -> {
			((MockHttpServletResponse) ((HttpServletResponseWrapper) res).getResponse()).setStatus(202);
			res.getOutputStream().write("{\"status\":\"PENDING\"}".getBytes(StandardCharsets.UTF_8));
		};

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(202);
		verify(idempotencyService).begin(userId, "wd-encoded-key", expectedHash);
		verify(idempotencyService).markPending(userId, "wd-encoded-key", leaseToken);
		verify(idempotencyService, never()).complete(any(), anyString(), any(), anyInt(), anyString());
		verify(idempotencyService, never()).release(any(), anyString(), any());
	}

	@Test
	void completeDbFailureAfterCommitted200DoesNotReleaseKeyAndPreservesResponseBody() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/transfers");
		request.addHeader(IdempotencyFilter.HEADER, "tx-complete-err");
		request.setContent("{\"amount\":100000}".getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse response = new MockHttpServletResponse();

		UUID leaseToken = UUID.randomUUID();
		when(idempotencyService.begin(any(), anyString(), anyString())).thenReturn(new Decision.Acquired(leaseToken));
		doThrow(new DataAccessResourceFailureException("db blip"))
				.when(idempotencyService).complete(userId, "tx-complete-err", leaseToken, 200, "{\"status\":\"COMPLETED\"}");
		FilterChain chain = (req, res) -> {
			((MockHttpServletResponse) ((HttpServletResponseWrapper) res).getResponse()).setStatus(200);
			res.getOutputStream().write("{\"status\":\"COMPLETED\"}".getBytes(StandardCharsets.UTF_8));
		};

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getContentAsString()).isEqualTo("{\"status\":\"COMPLETED\"}");
		verify(idempotencyService, never()).release(any(), anyString(), any());
	}

	@Test
	void markPendingDbFailureAfterDeposit202DoesNotReleaseKeyAndPreservesResponseBody() throws Exception {
		UUID walletId = UUID.randomUUID();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/wallets/" + walletId + "/deposit");
		request.addHeader(IdempotencyFilter.HEADER, "dep-pending-err");
		request.setContent("{\"amount\":100000}".getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse response = new MockHttpServletResponse();

		UUID leaseToken = UUID.randomUUID();
		when(idempotencyService.begin(any(), anyString(), anyString())).thenReturn(new Decision.Acquired(leaseToken));
		doThrow(new DataAccessResourceFailureException("db blip"))
				.when(idempotencyService).markPending(userId, "dep-pending-err", leaseToken);
		FilterChain chain = (req, res) -> {
			((MockHttpServletResponse) ((HttpServletResponseWrapper) res).getResponse()).setStatus(202);
			res.getOutputStream().write("{\"status\":\"PENDING\"}".getBytes(StandardCharsets.UTF_8));
		};

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(202);
		assertThat(response.getContentAsString()).isEqualTo("{\"status\":\"PENDING\"}");
		verify(idempotencyService, never()).release(any(), anyString(), any());
	}

	@Test
	void releaseDbFailureAfter422StillCopiesOriginalErrorBodyToResponse() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/transfers");
		request.addHeader(IdempotencyFilter.HEADER, "tx-release-err");
		request.setContent("{\"amount\":100000}".getBytes(StandardCharsets.UTF_8));
		MockHttpServletResponse response = new MockHttpServletResponse();

		UUID leaseToken = UUID.randomUUID();
		when(idempotencyService.begin(any(), anyString(), anyString())).thenReturn(new Decision.Acquired(leaseToken));
		doThrow(new DataAccessResourceFailureException("db blip"))
				.when(idempotencyService).release(userId, "tx-release-err", leaseToken);
		FilterChain chain = (req, res) -> {
			((MockHttpServletResponse) ((HttpServletResponseWrapper) res).getResponse()).setStatus(422);
			res.getOutputStream().write("{\"error\":\"INSUFFICIENT_FUNDS\"}".getBytes(StandardCharsets.UTF_8));
		};

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(422);
		assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"INSUFFICIENT_FUNDS\"}");
		verify(idempotencyService).release(userId, "tx-release-err", leaseToken);
	}
}
