package com.lakeserl.mini_banking.query.api;

import java.util.Objects;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
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

	private final ProjectionRebuildService rebuildService;

	public AdminProjectionController(ProjectionRebuildService rebuildService) {
		this.rebuildService = Objects.requireNonNull(rebuildService, "rebuildService");
	}

	@PostMapping("/rebuild")
	public RebuildResult rebuild(Authentication authentication) {
		if (authentication == null || authentication.getAuthorities().stream()
				.noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
			throw new AccessDeniedException("ROLE_ADMIN is required to rebuild projections");
		}
		return rebuildService.rebuildAll();
	}
}
