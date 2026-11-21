package com.lakeserl.mini_banking.reconciliation;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.lakeserl.mini_banking.query.api.CursorCodec;
import com.lakeserl.mini_banking.query.api.CursorCodec.Cursor;
import com.lakeserl.mini_banking.reconciliation.InternalReconciliationService.ReconciliationRunResult;
import com.lakeserl.mini_banking.reconciliation.ReconciliationIssueRepository.ReconciliationIssue;

/**
 * Administrative endpoints to run internal/bank reconciliation, inspect open/resolved issues,
 * and manually resolve issues with full actor audit trail.
 */
@RestController
@RequestMapping("/api/admin/reconciliation")
public class AdminReconciliationController {

	private static final Logger log = LoggerFactory.getLogger(AdminReconciliationController.class);
	private static final int MAX_LIMIT = 100;

	public record ResolveIssueRequest(@NotBlank String resolutionNotes) {
	}

	public record ReconciliationIssuePage(List<ReconciliationIssue> items, String nextCursor, boolean hasMore) {
	}

	private final InternalReconciliationService internalService;
	private final BankReconciliationService bankService;
	private final ReconciliationIssueRepository issueRepository;
	private final Clock clock;

	public AdminReconciliationController(
			InternalReconciliationService internalService,
			BankReconciliationService bankService,
			ReconciliationIssueRepository issueRepository,
			Clock clock) {
		this.internalService = Objects.requireNonNull(internalService, "internalService");
		this.bankService = Objects.requireNonNull(bankService, "bankService");
		this.issueRepository = Objects.requireNonNull(issueRepository, "issueRepository");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	@PostMapping("/internal/run")
	public ReconciliationRunResult runInternal(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
		String actor = requireAdminActor(jwt, authentication);
		log.info("Admin audit: actor={} triggered internal reconciliation", actor);
		return internalService.run();
	}

	@PostMapping("/bank/run")
	public ReconciliationRunResult runBank(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
			@AuthenticationPrincipal Jwt jwt,
			Authentication authentication) {
		String actor = requireAdminActor(jwt, authentication);
		LocalDate targetDate = (date != null)
				? date
				: clock.instant().atZone(BankReconciliationService.BANK_ZONE).toLocalDate();
		log.info("Admin audit: actor={} triggered bank reconciliation for date={}", actor, targetDate);
		return bankService.reconcileDate(targetDate);
	}

	@GetMapping("/issues")
	public ReconciliationIssuePage listIssues(
			@RequestParam(defaultValue = "OPEN") ReconciliationIssueStatus status,
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int limit,
			@AuthenticationPrincipal Jwt jwt,
			Authentication authentication) {
		requireAdminActor(jwt, authentication);
		if (limit < 1 || limit > MAX_LIMIT) {
			throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
		}
		Cursor decoded = (cursor == null) ? null : CursorCodec.decode(cursor);
		List<ReconciliationIssue> fetched = issueRepository.findByStatus(status, decoded, limit + 1);
		boolean hasMore = fetched.size() > limit;
		List<ReconciliationIssue> pageItems = hasMore ? fetched.subList(0, limit) : fetched;
		String nextCursor = hasMore
				? CursorCodec.encode(pageItems.getLast().detectedAt(), pageItems.getLast().id())
				: null;
		return new ReconciliationIssuePage(pageItems, nextCursor, hasMore);
	}

	@PostMapping("/issues/{id}/resolve")
	public ReconciliationIssue resolveIssue(
			@PathVariable long id,
			@Valid @RequestBody ResolveIssueRequest request,
			@AuthenticationPrincipal Jwt jwt,
			Authentication authentication) {
		String actor = requireAdminActor(jwt, authentication);
		Instant resolvedAt = clock.instant();
		ReconciliationIssue resolved = issueRepository.resolve(id, actor, request.resolutionNotes().trim(), resolvedAt);
		log.info("Admin audit: actor={} resolved reconciliation issue id={}", actor, id);
		return resolved;
	}

	private static String requireAdminActor(Jwt jwt, Authentication auth) {
		if (auth == null || auth.getAuthorities().stream()
				.noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
			throw new AccessDeniedException("ROLE_ADMIN is required for reconciliation operations");
		}
		return (jwt != null && jwt.getSubject() != null && !jwt.getSubject().isBlank())
				? jwt.getSubject()
				: auth.getName();
	}
}
