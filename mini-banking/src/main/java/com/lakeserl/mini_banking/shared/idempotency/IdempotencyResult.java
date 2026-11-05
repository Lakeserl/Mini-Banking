package com.lakeserl.mini_banking.shared.idempotency;

import java.util.Objects;

/** Result of evaluating an {@code Idempotency-Key} at the start of a command (ADR-010). */
public sealed interface IdempotencyResult
		permits IdempotencyResult.New, IdempotencyResult.InProgress, IdempotencyResult.Replay,
		IdempotencyResult.HashMismatch {

	record New() implements IdempotencyResult {
	}

	record InProgress() implements IdempotencyResult {
	}

	record Replay(String responsePayload) implements IdempotencyResult {
		public Replay {
			Objects.requireNonNull(responsePayload, "responsePayload must not be null");
		}
	}

	record HashMismatch() implements IdempotencyResult {
	}
}
