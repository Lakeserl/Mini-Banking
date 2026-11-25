package com.lakeserl.mini_banking.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
}
