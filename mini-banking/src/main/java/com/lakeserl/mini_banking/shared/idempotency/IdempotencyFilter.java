package com.lakeserl.mini_banking.shared.idempotency;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import com.lakeserl.mini_banking.shared.idempotency.IdempotencyService.Decision;

/**
 * Enforces {@code Idempotency-Key} header on money-moving endpoints (ADR-010).
 * Registered after JWT authentication so {@code user_id} is taken from the verified {@link Jwt} subject.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

	public static final String HEADER = "Idempotency-Key";
	private static final int MAX_KEY_LENGTH = 100;
	private static final Pattern IDEMPOTENT_PATH = Pattern
			.compile("^/api/(wallets/[^/]+/(deposit|withdraw)|transfers(/[^/]+/refund)?)$");

	private final IdempotencyService idempotencyService;

	public IdempotencyFilter(IdempotencyService idempotencyService) {
		this.idempotencyService = Objects.requireNonNull(idempotencyService, "idempotencyService must not be null");
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !"POST".equalsIgnoreCase(request.getMethod())
				|| !IDEMPOTENT_PATH.matcher(request.getRequestURI()).matches();
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)) {
			filterChain.doFilter(request, response);
			return;
		}

		UUID userId;
		try {
			userId = UUID.fromString(jwt.getSubject());
		} catch (Exception e) {
			writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_SUBJECT",
					"JWT subject must be a valid UUID");
			return;
		}

		String key = request.getHeader(HEADER);
		if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
			writeJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
					"Header Idempotency-Key is required and must be at most 100 characters");
			return;
		}

		CachedBodyRequest cachedRequest = new CachedBodyRequest(request);
		String hash = IdempotencyService.sha256(request.getMethod(), request.getRequestURI(), cachedRequest.body());

		Decision decision = idempotencyService.begin(userId, key, hash);
		switch (decision) {
			case Decision.InProgress() -> {
				writeJsonError(response, HttpServletResponse.SC_CONFLICT, "IDEMPOTENCY_IN_PROGRESS",
						"A request with this Idempotency-Key is currently in progress");
			}
			case Decision.HashMismatch() -> {
				writeJsonError(response, 422, "IDEMPOTENCY_KEY_CONFLICT",
						"Idempotency-Key was already used with a different request payload");
			}
			case Decision.Completed(int status, String body) -> {
				response.setStatus(status);
				response.setContentType(MediaType.APPLICATION_JSON_VALUE);
				response.setCharacterEncoding(StandardCharsets.UTF_8.name());
				response.getWriter().write(body);
			}
			case Decision.Acquired() -> {
				ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
				boolean completed = false;
				try {
					filterChain.doFilter(cachedRequest, wrappedResponse);
					int status = wrappedResponse.getStatus();
					if (status >= 200 && status < 300) {
						String body = new String(wrappedResponse.getContentAsByteArray(), StandardCharsets.UTF_8);
						idempotencyService.complete(userId, key, status, body);
						completed = true;
					}
				} finally {
					if (!completed) {
						idempotencyService.release(userId, key);
					}
					wrappedResponse.copyBodyToResponse();
				}
			}
		}
	}

	private void writeJsonError(HttpServletResponse response, int status, String error, String message)
			throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write("{\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
	}

	private static final class CachedBodyRequest extends HttpServletRequestWrapper {

		private final byte[] body;

		CachedBodyRequest(HttpServletRequest request) throws IOException {
			super(request);
			this.body = request.getInputStream().readAllBytes();
		}

		byte[] body() {
			return body;
		}

		@Override
		public ServletInputStream getInputStream() {
			ByteArrayInputStream bais = new ByteArrayInputStream(body);
			return new ServletInputStream() {
				@Override
				public int read() {
					return bais.read();
				}

				@Override
				public boolean isFinished() {
					return bais.available() == 0;
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setReadListener(ReadListener readListener) {
				}
			};
		}

		@Override
		public BufferedReader getReader() {
			return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
		}
	}
}
