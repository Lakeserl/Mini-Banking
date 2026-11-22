package com.lakeserl.mini_banking.shared.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.FilterChain;

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
	void allowsRequestBodyAtExactLimit() throws Exception {
		byte[] exactLimit = new byte[IdempotencyFilter.MAX_BODY_BYTES];
		for (int i = 0; i < exactLimit.length; i++) {
			exactLimit[i] = 'a';
		}
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/transfers");
		request.addHeader(IdempotencyFilter.HEADER, "idem-exact-limit");
		request.setContent(exactLimit);
		MockHttpServletResponse response = new MockHttpServletResponse();

		OffsetDateTime acquiredAt = OffsetDateTime.of(2026, 11, 22, 2, 0, 0, 0, ZoneOffset.UTC);
		when(idempotencyService.begin(any(), anyString(), anyString())).thenReturn(new Decision.Acquired(acquiredAt));
		FilterChain chain = (req, res) -> {
			byte[] read = req.getInputStream().readAllBytes();
			assertThat(read).hasSize(IdempotencyFilter.MAX_BODY_BYTES);
			((MockHttpServletResponse) ((jakarta.servlet.http.HttpServletResponseWrapper) res).getResponse()).setStatus(200);
			res.getOutputStream().write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
		};

		filter.doFilter(request, response, chain);

		assertThat(response.getStatus()).isEqualTo(200);
		verify(idempotencyService).complete(userId, "idem-exact-limit", acquiredAt, 200, "{\"ok\":true}");
	}
}
