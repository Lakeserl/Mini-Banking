package com.lakeserl.mini_banking.shared.idempotency;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import com.lakeserl.mini_banking.shared.api.ApiExceptionHandler.ErrorResponse;
import com.lakeserl.mini_banking.shared.idempotency.IdempotencyService.Decision;

import tools.jackson.databind.json.JsonMapper;

/**
 * Enforces {@code Idempotency-Key} header on money-moving endpoints (ADR-010).
 * Registered after JWT authentication so {@code user_id} is taken from the verified {@link Jwt} subject.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

	public static final String HEADER = "Idempotency-Key";
	public static final int MAX_BODY_BYTES = 64 * 1024;
	private static final int MAX_KEY_LENGTH = 100;
	private static final int PAYLOAD_TOO_LARGE_STATUS = 413;
	private static final PathPatternParser PATH_PARSER = PathPatternParser.defaultInstance;
	private static final PathPattern DEPOSIT_PATH = PATH_PARSER.parse("/api/wallets/{id}/deposit");
	private static final List<PathPattern> IDEMPOTENT_PATHS = List.of(
			PATH_PARSER.parse("/api/transfers"),
			PATH_PARSER.parse("/api/transfers/{id}/refund"),
			DEPOSIT_PATH,
			PATH_PARSER.parse("/api/wallets/{id}/withdraw"));

	private final IdempotencyService idempotencyService;
	private final JsonMapper mapper = JsonMapper.builder().build();

	public IdempotencyFilter(IdempotencyService idempotencyService) {
		this.idempotencyService = Objects.requireNonNull(idempotencyService, "idempotencyService must not be null");
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		if (!"POST".equalsIgnoreCase(request.getMethod())) {
			return true;
		}
		PathContainer pathWithinApp = parsePathWithinApplication(request);
		if (pathWithinApp == null) {
			return true;
		}
		for (PathPattern pattern : IDEMPOTENT_PATHS) {
			if (pattern.matches(pathWithinApp)) {
				return false;
			}
		}
		return true;
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

		if (request.getContentLengthLong() > MAX_BODY_BYTES) {
			writeJsonError(response, PAYLOAD_TOO_LARGE_STATUS, "PAYLOAD_TOO_LARGE",
					"Request body exceeds maximum allowed size of " + MAX_BODY_BYTES + " bytes");
			return;
		}

		CachedBodyRequest cachedRequest;
		try {
			cachedRequest = new CachedBodyRequest(request, MAX_BODY_BYTES);
		} catch (PayloadTooLargeException ex) {
			writeJsonError(response, PAYLOAD_TOO_LARGE_STATUS, "PAYLOAD_TOO_LARGE",
					"Request body exceeds maximum allowed size of " + MAX_BODY_BYTES + " bytes");
			return;
		}
		PathContainer pathWithinApp = parsePathWithinApplication(request);
		String canonicalPath = pathWithinApp != null ? canonicalPath(pathWithinApp) : request.getRequestURI();
		String hash = IdempotencyService.sha256(request.getMethod(), canonicalPath, cachedRequest.body());

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
			case Decision.Acquired(UUID leaseToken) -> {
				ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
				boolean committed2xx = false;
				try {
					filterChain.doFilter(cachedRequest, wrappedResponse);
					int status = wrappedResponse.getStatus();
					if (status == HttpServletResponse.SC_ACCEPTED
							&& pathWithinApp != null
							&& DEPOSIT_PATH.matches(pathWithinApp)) {
						committed2xx = true;
						try {
							idempotencyService.markPending(userId, key, leaseToken);
						} catch (RuntimeException persistEx) {
							log.error("Failed to mark idempotency key pending after HTTP {} for user {}",
									status, userId, persistEx);
						}
					} else if (status >= 200 && status < 300) {
						committed2xx = true;
						String body = new String(wrappedResponse.getContentAsByteArray(), StandardCharsets.UTF_8);
						try {
							idempotencyService.complete(userId, key, leaseToken, status, body);
						} catch (RuntimeException persistEx) {
							log.error("Failed to persist idempotency completion after HTTP {} for user {}",
									status, userId, persistEx);
						}
					}
				} finally {
					if (!committed2xx) {
						try {
							idempotencyService.release(userId, key, leaseToken);
						} catch (RuntimeException releaseEx) {
							log.error("Failed to release idempotency key for user {}", userId, releaseEx);
						}
					}
					wrappedResponse.copyBodyToResponse();
				}
			}
		}
	}

	private static PathContainer parsePathWithinApplication(HttpServletRequest request) {
		String requestUri = request.getRequestURI();
		if (requestUri == null) {
			return null;
		}
		try {
			return RequestPath.parse(requestUri, request.getContextPath()).pathWithinApplication();
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static String canonicalPath(PathContainer pathWithinApp) {
		StringBuilder sb = new StringBuilder();
		for (PathContainer.Element element : pathWithinApp.elements()) {
			if (element instanceof PathContainer.PathSegment segment) {
				sb.append(segment.valueToMatch());
			} else {
				sb.append(element.value());
			}
		}
		return sb.toString();
	}

	private void writeJsonError(HttpServletResponse response, int status, String error, String message)
			throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(mapper.writeValueAsString(new ErrorResponse(error, message)));
	}

	private static final class PayloadTooLargeException extends RuntimeException {
	}

	private static final class CachedBodyRequest extends HttpServletRequestWrapper {

		private final byte[] body;

		CachedBodyRequest(HttpServletRequest request, int maxBytes) throws IOException {
			super(request);
			this.body = readBounded(request.getInputStream(), maxBytes);
		}

		private static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[4096];
			int total = 0;
			int read;
			while ((read = in.read(buffer)) != -1) {
				total += read;
				if (total > maxBytes) {
					throw new PayloadTooLargeException();
				}
				out.write(buffer, 0, read);
			}
			return out.toByteArray();
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
