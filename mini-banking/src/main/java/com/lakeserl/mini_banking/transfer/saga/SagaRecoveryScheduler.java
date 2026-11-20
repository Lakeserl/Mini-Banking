package com.lakeserl.mini_banking.transfer.saga;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.lakeserl.mini_banking.transfer.infrastructure.TransferSagaStateStore;
import com.lakeserl.mini_banking.transfer.infrastructure.TransferSagaStateStore.StuckSaga;

/**
 * Periodically scans {@code transfer_saga_state} for non-terminal transfer sagas that have been idle
 * longer than {@code transfer.recovery.stuck-threshold-seconds} and resumes them via
 * {@link TransferSagaOrchestrator#resume(java.util.UUID, int, int)}.
 */
@Component
@ConditionalOnProperty(name = "transfer.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class SagaRecoveryScheduler {

	public static final long DEFAULT_STUCK_THRESHOLD_SECONDS = 30L;
	public static final int DEFAULT_MAX_RETRIES = 3;
	public static final int DEFAULT_BATCH_SIZE = 50;

	private static final Logger log = LoggerFactory.getLogger(SagaRecoveryScheduler.class);

	private final TransferSagaStateStore sagaStateStore;
	private final TransferSagaOrchestrator orchestrator;
	private final Duration stuckThreshold;
	private final int maxRetries;
	private final int batchSize;

	@Autowired
	public SagaRecoveryScheduler(TransferSagaStateStore sagaStateStore,
			TransferSagaOrchestrator orchestrator,
			@Value("${transfer.recovery.stuck-threshold-seconds:30}") long stuckThresholdSeconds,
			@Value("${transfer.recovery.max-retries:3}") int maxRetries,
			@Value("${transfer.recovery.batch-size:50}") int batchSize) {
		this(sagaStateStore, orchestrator, Duration.ofSeconds(stuckThresholdSeconds), maxRetries, batchSize);
	}

	public SagaRecoveryScheduler(TransferSagaStateStore sagaStateStore,
			TransferSagaOrchestrator orchestrator,
			Duration stuckThreshold,
			int maxRetries,
			int batchSize) {
		this.sagaStateStore = Objects.requireNonNull(sagaStateStore, "sagaStateStore must not be null");
		this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator must not be null");
		if (stuckThreshold == null || stuckThreshold.isNegative() || stuckThreshold.isZero()) {
			throw new IllegalArgumentException(
					"transfer.recovery.stuck-threshold-seconds must be > 0, got " + stuckThreshold);
		}
		if (maxRetries < 0) {
			throw new IllegalArgumentException(
					"transfer.recovery.max-retries must be >= 0, got " + maxRetries);
		}
		if (batchSize <= 0) {
			throw new IllegalArgumentException(
					"transfer.recovery.batch-size must be > 0, got " + batchSize);
		}
		this.stuckThreshold = stuckThreshold;
		this.maxRetries = maxRetries;
		this.batchSize = batchSize;
	}

	@Scheduled(fixedDelayString = "${transfer.recovery.poll-interval-ms:10000}")
	public void schedule() {
		try {
			recoverOnce();
		} catch (RuntimeException ex) {
			log.warn("Saga recovery sweep failed; will retry", ex);
		}
	}

	public int recoverOnce() {
		List<StuckSaga> stuckList = sagaStateStore.findStuck(stuckThreshold, batchSize);
		int processed = 0;
		for (StuckSaga stuck : stuckList) {
			try {
				int attempt = sagaStateStore.incrementRetry(stuck.transferId());
				orchestrator.resume(stuck.transferId(), attempt, maxRetries);
				processed++;
			} catch (Exception ex) {
				log.warn("Failed to recover stuck transfer saga {} (status={}, retryCount={}); continuing batch",
						stuck.transferId(), stuck.status(), stuck.retryCount(), ex);
			}
		}
		return processed;
	}
}
