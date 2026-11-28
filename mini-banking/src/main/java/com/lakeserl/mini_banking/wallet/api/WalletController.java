package com.lakeserl.mini_banking.wallet.api;

import java.util.Objects;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.lakeserl.mini_banking.deposit.command.DepositCommandHandler;
import com.lakeserl.mini_banking.deposit.domain.DepositStatus;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.DeterministicIds;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.idempotency.IdempotencyFilter;
import com.lakeserl.mini_banking.wallet.command.WalletCommandHandler;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.domain.WalletStatus;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletNotFoundException;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;
import com.lakeserl.mini_banking.withdrawal.command.WithdrawalCommandHandler;
import com.lakeserl.mini_banking.withdrawal.domain.Withdrawal;
import com.lakeserl.mini_banking.withdrawal.domain.WithdrawalStatus;

@RestController
@RequestMapping("/api/wallets")
public class WalletController {

	private static final UUID DEPOSIT_KEY_NAMESPACE = UUID.fromString("c3a1f7b2-8d40-4e95-b102-6f4a8e9c3d21");
	private static final UUID WITHDRAWAL_KEY_NAMESPACE = UUID.fromString("7e4c9a1d-3b52-4f86-9c21-5a8b7d6e4f32");

	public record OpenWalletRequest(Currency currency) {
	}

	public record OpenWalletResponse(UUID walletId, UUID ownerId, Currency currency, WalletStatus status) {
	}

	public record DepositRequest(
			@Positive long amount,
			@NotNull Currency currency,
			@NotBlank @Size(max = 100) @Pattern(regexp = "^[A-Za-z0-9._-]*$", message = "must contain only ASCII letters, digits, '.', '_', or '-'") String bankReference) {
	}

	public record DepositResponse(UUID depositId, UUID walletId, DepositStatus status) {
	}

	public record WithdrawRequest(@Positive long amount, @NotNull Currency currency, @NotBlank String bankAccount) {
		@Override
		public String toString() {
			String masked = (bankAccount == null || bankAccount.isBlank())
					? "null"
					: Withdrawal.maskAccount(bankAccount);
			return "WithdrawRequest[amount=" + amount + ", currency=" + currency + ", bankAccount=" + masked + "]";
		}
	}

	public record WithdrawResponse(UUID withdrawalId, UUID walletId, WithdrawalStatus status) {
	}

	public record LockWalletRequest(@NotBlank String reason) {
	}

	public record LockWalletResponse(UUID walletId, WalletStatus status) {
	}

	private final WalletCommandHandler walletCommandHandler;
	private final WalletRepository walletRepository;
	private final DepositCommandHandler depositCommandHandler;
	private final WithdrawalCommandHandler withdrawalCommandHandler;

	public WalletController(WalletCommandHandler walletCommandHandler, WalletRepository walletRepository,
			DepositCommandHandler depositCommandHandler, WithdrawalCommandHandler withdrawalCommandHandler) {
		this.walletCommandHandler = Objects.requireNonNull(walletCommandHandler, "walletCommandHandler must not be null");
		this.walletRepository = Objects.requireNonNull(walletRepository, "walletRepository must not be null");
		this.depositCommandHandler = Objects.requireNonNull(depositCommandHandler,
				"depositCommandHandler must not be null");
		this.withdrawalCommandHandler = Objects.requireNonNull(withdrawalCommandHandler,
				"withdrawalCommandHandler must not be null");
	}

	@PostMapping
	public ResponseEntity<OpenWalletResponse> openWallet(
			@RequestBody(required = false) OpenWalletRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		UUID ownerId = requireUserId(jwt);
		Currency currency = (request == null || request.currency() == null) ? Currency.VND : request.currency();
		UUID walletId = walletCommandHandler.openWallet(ownerId, currency);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(new OpenWalletResponse(walletId, ownerId, currency, WalletStatus.ACTIVE));
	}

	@PostMapping("/{id}/deposit")
	public ResponseEntity<DepositResponse> deposit(
			@PathVariable UUID id,
			@RequestHeader(IdempotencyFilter.HEADER) String idempotencyKey,
			@Valid @RequestBody DepositRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		UUID userId = requireUserId(jwt);
		requireOwner(id, userId, false, null);

		UUID depositId = DeterministicIds.v5(DEPOSIT_KEY_NAMESPACE, userId + "|" + idempotencyKey);
		DepositStatus status = depositCommandHandler.deposit(depositId, id,
				new Money(request.amount(), request.currency()), request.bankReference());
		HttpStatus httpStatus = (status == DepositStatus.PENDING) ? HttpStatus.ACCEPTED : HttpStatus.OK;
		return ResponseEntity.status(httpStatus).body(new DepositResponse(depositId, id, status));
	}

	@PostMapping("/{id}/withdraw")
	public ResponseEntity<WithdrawResponse> withdraw(
			@PathVariable UUID id,
			@RequestHeader(IdempotencyFilter.HEADER) String idempotencyKey,
			@Valid @RequestBody WithdrawRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		UUID userId = requireUserId(jwt);
		requireOwner(id, userId, false, null);

		UUID withdrawalId = DeterministicIds.v5(WITHDRAWAL_KEY_NAMESPACE, userId + "|" + idempotencyKey);
		WithdrawalStatus status = withdrawalCommandHandler.withdraw(withdrawalId, id,
				new Money(request.amount(), request.currency()), request.bankAccount());
		return ResponseEntity.ok(new WithdrawResponse(withdrawalId, id, status));
	}

	@PostMapping("/{id}/lock")
	public ResponseEntity<LockWalletResponse> lockWallet(
			@PathVariable UUID id,
			@Valid @RequestBody LockWalletRequest request,
			@AuthenticationPrincipal Jwt jwt,
			Authentication authentication) {
		UUID userId = requireUserId(jwt);
		requireOwner(id, userId, true, authentication);

		walletCommandHandler.lockWallet(id, request.reason());
		return ResponseEntity.ok(new LockWalletResponse(id, WalletStatus.LOCKED));
	}

	private Wallet requireOwner(UUID walletId, UUID userId, boolean allowAdmin, Authentication auth) {
		Wallet wallet = walletRepository.load(walletId).orElseThrow(() -> new WalletNotFoundException(walletId));
		if (wallet.ownerId().equals(userId)) {
			return wallet;
		}
		if (allowAdmin && auth != null && auth.getAuthorities().stream()
				.anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
			return wallet;
		}
		throw new AccessDeniedException("User " + userId + " is not authorized to access wallet " + walletId);
	}

	private UUID requireUserId(Jwt jwt) {
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
