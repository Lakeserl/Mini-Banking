package com.lakeserl.mini_banking.transfer.api;

import java.util.Objects;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.DeterministicIds;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.shared.idempotency.IdempotencyFilter;
import com.lakeserl.mini_banking.transfer.domain.Transfer;
import com.lakeserl.mini_banking.transfer.domain.TransferStatus;
import com.lakeserl.mini_banking.transfer.domain.TransferType;
import com.lakeserl.mini_banking.transfer.domain.exceptions.TransferNotFoundException;
import com.lakeserl.mini_banking.transfer.saga.TransferSagaOrchestrator;
import com.lakeserl.mini_banking.wallet.domain.Wallet;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletNotFoundException;
import com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

	private static final UUID TRANSFER_KEY_NAMESPACE = UUID.fromString("2b8e5c4a-9f13-4d72-8a61-3e7c9f4b2d15");
	private static final UUID REFUND_KEY_NAMESPACE = UUID.fromString("6c1d9e3b-5a84-4f29-b703-8d2f4a6c9e51");

	public record TransferRequest(
			@NotNull UUID sourceWalletId,
			@NotNull UUID destinationWalletId,
			@Positive long amount,
			@NotNull Currency currency) {
	}

	public record TransferResponse(
			UUID transferId,
			TransferStatus status,
			TransferType type,
			String failureReason) {

		public static TransferResponse from(Transfer t) {
			return new TransferResponse(t.id(), t.status(), t.type(), t.failureReason());
		}
	}

	public record TransferStatusResponse(
			UUID transferId,
			UUID sourceWalletId,
			UUID destinationWalletId,
			long amount,
			Currency currency,
			TransferStatus status,
			TransferType type,
			UUID originalTransferId,
			String failureReason) {

		public static TransferStatusResponse from(Transfer t) {
			return new TransferStatusResponse(
					t.id(),
					t.sourceWalletId(),
					t.destinationWalletId(),
					t.amount().amount(),
					t.amount().currency(),
					t.status(),
					t.type(),
					t.originalTransferId(),
					t.failureReason());
		}
	}

	private final TransferSagaOrchestrator orchestrator;
	private final WalletRepository walletRepository;

	public TransferController(TransferSagaOrchestrator orchestrator, WalletRepository walletRepository) {
		this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator must not be null");
		this.walletRepository = Objects.requireNonNull(walletRepository, "walletRepository must not be null");
	}

	@PostMapping
	public ResponseEntity<TransferResponse> transfer(
			@RequestHeader(IdempotencyFilter.HEADER) String idempotencyKey,
			@Valid @RequestBody TransferRequest request,
			@AuthenticationPrincipal Jwt jwt) {
		UUID userId = requireUserId(jwt);
		requireSourceWalletOwner(request.sourceWalletId(), userId);

		UUID transferId = DeterministicIds.v5(TRANSFER_KEY_NAMESPACE, userId + "|" + idempotencyKey);
		Transfer transfer = orchestrator.transfer(
				transferId,
				request.sourceWalletId(),
				request.destinationWalletId(),
				new Money(request.amount(), request.currency()));
		HttpStatus httpStatus = transfer.status().isTerminal() ? HttpStatus.OK : HttpStatus.ACCEPTED;
		return ResponseEntity.status(httpStatus).body(TransferResponse.from(transfer));
	}

	@PostMapping("/{id}/refund")
	public ResponseEntity<TransferResponse> refund(
			@PathVariable UUID id,
			@RequestHeader(IdempotencyFilter.HEADER) String idempotencyKey,
			@AuthenticationPrincipal Jwt jwt,
			Authentication authentication) {
		UUID userId = requireUserId(jwt);
		Transfer original = orchestrator.findTransfer(id)
				.orElseThrow(() -> new TransferNotFoundException(id));
		requireReceiverOrAdmin(original, userId, authentication);

		UUID refundTransferId = DeterministicIds.v5(REFUND_KEY_NAMESPACE,
				userId + "|" + id + "|" + idempotencyKey);
		Transfer refundTransfer = orchestrator.refund(refundTransferId, id);
		HttpStatus httpStatus = refundTransfer.status().isTerminal() ? HttpStatus.OK : HttpStatus.ACCEPTED;
		return ResponseEntity.status(httpStatus).body(TransferResponse.from(refundTransfer));
	}

	@GetMapping("/{id}")
	public ResponseEntity<TransferStatusResponse> getTransfer(
			@PathVariable UUID id,
			@AuthenticationPrincipal Jwt jwt,
			Authentication authentication) {
		UUID userId = requireUserId(jwt);
		Transfer transfer = orchestrator.findTransfer(id)
				.orElseThrow(() -> new TransferNotFoundException(id));
		requireParticipantOrAdmin(transfer, userId, authentication);
		return ResponseEntity.ok(TransferStatusResponse.from(transfer));
	}

	private void requireSourceWalletOwner(UUID sourceWalletId, UUID userId) {
		Wallet sourceWallet = walletRepository.load(sourceWalletId)
				.orElseThrow(() -> new WalletNotFoundException(sourceWalletId));
		if (!sourceWallet.ownerId().equals(userId)) {
			throw new AccessDeniedException(
					"User " + userId + " is not authorized to transfer from wallet " + sourceWalletId);
		}
	}

	private void requireReceiverOrAdmin(Transfer original, UUID userId, Authentication auth) {
		if (isAdmin(auth)) {
			return;
		}
		Wallet originalDestWallet = walletRepository.load(original.destinationWalletId())
				.orElseThrow(() -> new WalletNotFoundException(original.destinationWalletId()));
		if (originalDestWallet.ownerId().equals(userId)) {
			return;
		}
		throw new AccessDeniedException(
				"User " + userId + " is not authorized to refund transfer " + original.id());
	}

	private void requireParticipantOrAdmin(Transfer transfer, UUID userId, Authentication auth) {
		if (isAdmin(auth)) {
			return;
		}
		boolean ownsSource = walletRepository.load(transfer.sourceWalletId())
				.map(w -> w.ownerId().equals(userId))
				.orElse(false);
		if (ownsSource) {
			return;
		}
		boolean ownsDest = walletRepository.load(transfer.destinationWalletId())
				.map(w -> w.ownerId().equals(userId))
				.orElse(false);
		if (ownsDest) {
			return;
		}
		throw new AccessDeniedException(
				"User " + userId + " is not authorized to view transfer " + transfer.id());
	}

	private boolean isAdmin(Authentication auth) {
		return auth != null && auth.getAuthorities().stream()
				.anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
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
