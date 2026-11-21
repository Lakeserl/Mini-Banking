package com.lakeserl.mini_banking.query.api;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lakeserl.mini_banking.query.rebuild.ProjectionRebuildService;
import com.lakeserl.mini_banking.query.rebuild.ProjectionRebuildService.RebuildResult;

/**
 * Administrative endpoint to trigger a deterministic full projection rebuild from PostgreSQL {@code events}.
 */
@RestController
@RequestMapping("/api/admin/projections")
public class AdminProjectionController {

	private static final Logger log = LoggerFactory.getLogger(AdminProjectionController.class);

	private final ProjectionRebuildService rebuildService;

	public AdminProjectionController(ProjectionRebuildService rebuildService) {
		this.rebuildService = Objects.requireNonNull(rebuildService, "rebuildService");
	}

	@PostMapping("/rebuild")
	public RebuildResult rebuild(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
		if (authentication == null || authentication.getAuthorities().stream()
				.noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
			throw new AccessDeniedException("ROLE_ADMIN is required to rebuild projections");
		}
		String actor = (jwt != null && jwt.getSubject() != null) ? jwt.getSubject() : authentication.getName();
		log.info("Admin audit: actor={} triggered full projection rebuild", actor);
		return rebuildService.rebuildAll();
	}
}
