package com.lakeserl.mini_banking.query.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.lakeserl.mini_banking.query.api.CursorCodec.Cursor;
import com.lakeserl.mini_banking.query.api.QueryRepository.DailyReportRow;
import com.lakeserl.mini_banking.query.api.QueryRepository.TransactionHistoryRow;
import com.lakeserl.mini_banking.query.api.QueryRepository.WalletBalanceRow;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletNotFoundException;

/**
 * Read-side CQRS endpoints for wallet balances, cursor-paginated transaction history,
 * and daily settled reports (Asia/Ho_Chi_Minh).
 */
@RestController
@RequestMapping("/api")
public class WalletQueryController {

	public static final int DEFAULT_LIMIT = 20;
	public static final int MAX_LIMIT = 100;
	public static final long MAX_REPORT_DAYS = 90;

	public record WalletBalanceResponse(UUID walletId, UUID ownerId, long balance, String currency, String status,
			long version, Instant updatedAt) {
	}

	public record TransactionItem(UUID transactionId, String txType, String direction, long amount, String currency,
			String status, UUID counterpartWalletId, String counterpartLabel, String bankReference,
			UUID originalTransferId, String failureReason, Instant occurredAt, Instant updatedAt) {
	}

	public record TransactionHistoryPage(List<TransactionItem> items, String nextCursor, boolean hasMore) {
	}

	public record DailyReportResponse(LocalDate date, String currency, long totalDeposit, long totalWithdrawal,
			long totalTransfer, long totalRefund, long transactionCount) {
	}

	private final QueryRepository queryRepository;

	public WalletQueryController(QueryRepository queryRepository) {
		this.queryRepository = Objects.requireNonNull(queryRepository, "queryRepository");
	}

	@GetMapping("/wallets/{id}/balance")
	public WalletBalanceResponse getBalance(
			@PathVariable UUID id,
			@AuthenticationPrincipal Jwt jwt,
			Authentication authentication) {
		UUID userId = requireUserId(jwt);
		WalletBalanceRow row = requireAuthorizedWallet(id, userId, authentication);
		return new WalletBalanceResponse(row.walletId(), row.ownerId(), row.balance(), row.currency(),
				row.status(), row.version(), row.updatedAt());
	}

	@GetMapping("/wallets/{id}/transactions")
	public TransactionHistoryPage getTransactions(
			@PathVariable UUID id,
			@RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int limit,
			@AuthenticationPrincipal Jwt jwt,
			Authentication authentication) {
		UUID userId = requireUserId(jwt);
		requireAuthorizedWallet(id, userId, authentication);

		if (limit < 1 || limit > MAX_LIMIT) {
			throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
		}
		Cursor decoded = (cursor == null) ? null : CursorCodec.decode(cursor);

		List<TransactionHistoryRow> fetched = queryRepository.findTransactions(id, decoded, limit + 1);
		boolean hasMore = fetched.size() > limit;
		List<TransactionHistoryRow> pageRows = hasMore ? fetched.subList(0, limit) : fetched;
		String nextCursor = hasMore
				? CursorCodec.encode(pageRows.getLast().occurredAt(), pageRows.getLast().id())
				: null;

		List<TransactionItem> items = pageRows.stream()
				.map(r -> new TransactionItem(r.transactionId(), r.txType(), r.direction(), r.amount(),
						r.currency(), r.status(), r.counterpartWalletId(), r.counterpartLabel(),
						r.bankReference(), r.originalTransferId(), r.failureReason(),
						r.occurredAt(), r.updatedAt()))
				.toList();
		return new TransactionHistoryPage(items, nextCursor, hasMore);
	}

	@GetMapping("/reports/daily")
	public List<DailyReportResponse> getDailyReports(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			Authentication authentication) {
		if (!isAdmin(authentication)) {
			throw new AccessDeniedException("ROLE_ADMIN is required to view daily reports");
		}
		if (from.isAfter(to)) {
			throw new IllegalArgumentException("from date must not be after to date");
		}
		if (ChronoUnit.DAYS.between(from, to) > MAX_REPORT_DAYS) {
			throw new IllegalArgumentException("date range must not exceed " + MAX_REPORT_DAYS + " days");
		}
		return queryRepository.findDailyReports(from, to).stream()
				.map(this::toDailyResponse)
				.toList();
	}

	private DailyReportResponse toDailyResponse(DailyReportRow row) {
		return new DailyReportResponse(row.date(), row.currency(), row.totalDeposit(), row.totalWithdrawal(),
				row.totalTransfer(), row.totalRefund(), row.transactionCount());
	}

	private WalletBalanceRow requireAuthorizedWallet(UUID walletId, UUID userId, Authentication auth) {
		WalletBalanceRow row = queryRepository.findBalance(walletId)
				.orElseThrow(() -> new WalletNotFoundException(walletId));
		if (row.ownerId().equals(userId) || isAdmin(auth)) {
			return row;
		}
		throw new AccessDeniedException("User " + userId + " is not authorized to access wallet " + walletId);
	}

	private static boolean isAdmin(Authentication auth) {
		return auth != null && auth.getAuthorities().stream()
				.anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
	}

	private static UUID requireUserId(Jwt jwt) {
		if (jwt == null || jwt.getSubject() == null) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing JWT subject");
		}
		try {
			return UUID.fromString(jwt.getSubject());
		} catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT subject must be a valid UUID");
		}
	}
}
