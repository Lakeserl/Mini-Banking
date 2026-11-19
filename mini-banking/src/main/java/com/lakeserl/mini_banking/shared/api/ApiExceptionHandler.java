package com.lakeserl.mini_banking.shared.api;

import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.lakeserl.mini_banking.shared.domain.CurrencyMismatchException;
import com.lakeserl.mini_banking.shared.eventsourcing.OptimisticConcurrencyException;
import com.lakeserl.mini_banking.transfer.domain.exceptions.InvalidTransferStateException;
import com.lakeserl.mini_banking.transfer.domain.exceptions.TransferNotFoundException;
import com.lakeserl.mini_banking.transfer.domain.exceptions.TransferPolicyViolationException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.ConflictingTransactionException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InsufficientFundsException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InvalidAmountException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletAlreadyExistsException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletLockedException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletNotFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

	public record ErrorResponse(String error, String message) {
	}

	@ExceptionHandler(WalletNotFoundException.class)
	ResponseEntity<ErrorResponse> handleNotFound(WalletNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ErrorResponse("WALLET_NOT_FOUND", ex.getMessage()));
	}

	@ExceptionHandler(TransferNotFoundException.class)
	ResponseEntity<ErrorResponse> handleTransferNotFound(TransferNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ErrorResponse("TRANSFER_NOT_FOUND", ex.getMessage()));
	}

	@ExceptionHandler(TransferPolicyViolationException.class)
	ResponseEntity<ErrorResponse> handleTransferPolicyViolation(TransferPolicyViolationException ex) {
		return ResponseEntity.status(422)
				.body(new ErrorResponse("TRANSFER_POLICY_VIOLATION", ex.getMessage()));
	}

	@ExceptionHandler(InvalidTransferStateException.class)
	ResponseEntity<ErrorResponse> handleInvalidTransferState(InvalidTransferStateException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ErrorResponse("INVALID_TRANSFER_STATE", ex.getMessage()));
	}

	@ExceptionHandler(WalletAlreadyExistsException.class)
	ResponseEntity<ErrorResponse> handleAlreadyExists(WalletAlreadyExistsException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ErrorResponse("WALLET_ALREADY_EXISTS", ex.getMessage()));
	}

	@ExceptionHandler(WalletLockedException.class)
	ResponseEntity<ErrorResponse> handleLocked(WalletLockedException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ErrorResponse("WALLET_LOCKED", ex.getMessage()));
	}

	@ExceptionHandler(OptimisticConcurrencyException.class)
	ResponseEntity<ErrorResponse> handleConcurrency(OptimisticConcurrencyException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ErrorResponse("CONCURRENCY_CONFLICT", ex.getMessage()));
	}

	@ExceptionHandler(InsufficientFundsException.class)
	ResponseEntity<ErrorResponse> handleInsufficientFunds(InsufficientFundsException ex) {
		return ResponseEntity.status(422)
				.body(new ErrorResponse("INSUFFICIENT_FUNDS", ex.getMessage()));
	}

	@ExceptionHandler(ConflictingTransactionException.class)
	ResponseEntity<ErrorResponse> handleConflictingTransaction(ConflictingTransactionException ex) {
		return ResponseEntity.status(422)
				.body(new ErrorResponse("CONFLICTING_TRANSACTION", ex.getMessage()));
	}

	@ExceptionHandler({ InvalidAmountException.class, CurrencyMismatchException.class, IllegalArgumentException.class })
	ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ErrorResponse("INVALID_REQUEST", ex.getMessage()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
		String details = ex.getBindingResult().getFieldErrors().stream()
				.map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
				.collect(Collectors.joining("; "));
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(new ErrorResponse("VALIDATION_FAILED", details));
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(new ErrorResponse("FORBIDDEN", ex.getMessage()));
	}
}
