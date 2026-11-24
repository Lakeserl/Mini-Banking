package com.lakeserl.mini_banking.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.lakeserl.mini_banking.query.api.CursorCodec.Cursor;
import com.lakeserl.mini_banking.reconciliation.ReconciliationIssueRepository.ReconciliationIssue;
import com.lakeserl.mini_banking.reconciliation.ReconciliationIssueRepository.ReconciliationIssueAlreadyResolvedException;
import com.lakeserl.mini_banking.reconciliation.ReconciliationIssueRepository.ReconciliationIssueNotFoundException;

class ReconciliationIssueRepositoryUnitTest {

	private JdbcTemplate jdbc;
	private ReconciliationIssueRepository repository;
	private final AtomicLong seq = new AtomicLong(0);
	private final Map<Long, ReconciliationIssue> store = new LinkedHashMap<>();
	private final List<String> capturedQuerySql = new ArrayList<>();

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		jdbc = mock(JdbcTemplate.class);
		when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
			String sql = inv.getArgument(0);
			Object[] args = inv.getArguments();
			if (sql.contains("INSERT INTO reconciliation_issues")) {
				ReconciliationIssueType type = ReconciliationIssueType.valueOf((String) args[1]);
				String dedupeKey = (String) args[2];
				String details = (String) args[3];
				Instant detectedAt = ((OffsetDateTime) args[4]).toInstant();
				boolean openExists = store.values().stream()
						.anyMatch(r -> r.dedupeKey().equals(dedupeKey) && r.status() == ReconciliationIssueStatus.OPEN);
				if (openExists) {
					return 0;
				}
				long id = seq.incrementAndGet();
				store.put(id, new ReconciliationIssue(id, type, dedupeKey, details, ReconciliationIssueStatus.OPEN,
						detectedAt, null, null, null));
				return 1;
			}
			throw new IllegalStateException("Unexpected update SQL: " + sql);
		});

		when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(inv -> {
			String sql = inv.getArgument(0);
			capturedQuerySql.add(sql);
			Object[] args = inv.getArguments();
			if (sql.contains("FROM reconciliation_issues") && sql.contains("ORDER BY detected_at DESC")) {
				return List.copyOf(store.values());
			}
			if (sql.contains("UPDATE reconciliation_issues")) {
				Instant resolvedAt = ((OffsetDateTime) args[2]).toInstant();
				String resolvedBy = (String) args[3];
				String notes = (String) args[4];
				long id = (Long) args[5];
				ReconciliationIssue cur = store.get(id);
				if (cur == null || cur.status() != ReconciliationIssueStatus.OPEN) {
					return List.of();
				}
				ReconciliationIssue updated = new ReconciliationIssue(cur.id(), cur.issueType(), cur.dedupeKey(),
						cur.detailsJson(), ReconciliationIssueStatus.RESOLVED, cur.detectedAt(), resolvedAt,
						resolvedBy, notes);
				store.put(id, updated);
				return List.of(updated);
			}
			if (sql.contains("SELECT status FROM reconciliation_issues")) {
				long id = (Long) args[2];
				ReconciliationIssue cur = store.get(id);
				return cur == null ? List.of() : List.of(cur.status().name());
			}
			throw new IllegalStateException("Unexpected query SQL: " + sql);
		});

		repository = new ReconciliationIssueRepository(jdbc);
	}

	@Test
	void openIfAbsentIsIdempotentWhileOpenAndAllowsReopeningAfterResolve() {
		Instant t1 = Instant.parse("2026-11-21T10:00:00Z");
		Instant t2 = Instant.parse("2026-11-21T11:00:00Z");

		assertThat(repository.openIfAbsent(ReconciliationIssueType.CLEARING_NON_ZERO, "CLEARING_NON_ZERO:GLOBAL",
				"{\"clearing\":1000}", t1)).isTrue();
		assertThat(repository.openIfAbsent(ReconciliationIssueType.CLEARING_NON_ZERO, "CLEARING_NON_ZERO:GLOBAL",
				"{\"clearing\":1000}", t1)).isFalse();
		assertThat(store).hasSize(1);

		ReconciliationIssue resolved = repository.resolve(1L, "admin-1", "Verified in-flight saga settled", t2);
		assertThat(resolved.status()).isEqualTo(ReconciliationIssueStatus.RESOLVED);
		assertThat(resolved.resolvedBy()).isEqualTo("admin-1");

		assertThat(repository.openIfAbsent(ReconciliationIssueType.CLEARING_NON_ZERO, "CLEARING_NON_ZERO:GLOBAL",
				"{\"clearing\":2000}", t2.plusSeconds(60))).isTrue();
		assertThat(store).hasSize(2);
	}

	@Test
	void resolveThrowsNotFoundOrAlreadyResolved() {
		Instant now = Instant.parse("2026-11-21T10:00:00Z");
		assertThatThrownBy(() -> repository.resolve(999L, "admin-1", "notes", now))
				.isInstanceOf(ReconciliationIssueNotFoundException.class);

		repository.openIfAbsent(ReconciliationIssueType.INVARIANT_VIOLATION, "INV:1", "{}", now);
		repository.resolve(1L, "admin-1", "fixed", now);

		assertThatThrownBy(() -> repository.resolve(1L, "admin-2", "again", now))
				.isInstanceOf(ReconciliationIssueAlreadyResolvedException.class);
	}

	@Test
	void sqlTemplatesPreserveWhitespaceBeforeColumnList() {
		Instant now = Instant.parse("2026-11-24T04:00:00Z");
		repository.openIfAbsent(ReconciliationIssueType.BALANCE_MISMATCH, "BAL:1", "{}", now);
		capturedQuerySql.clear();

		repository.findByStatus(ReconciliationIssueStatus.OPEN, null, 20);
		repository.findByStatus(ReconciliationIssueStatus.OPEN, new Cursor(now, 10L), 20);
		repository.resolve(1L, "admin-1", "fixed", now);

		assertThat(capturedQuerySql).hasSize(3);
		assertThat(capturedQuerySql.get(0)).contains("SELECT id,").doesNotContain("SELECTid");
		assertThat(capturedQuerySql.get(1)).contains("SELECT id,").doesNotContain("SELECTid");
		assertThat(capturedQuerySql.get(2)).contains("RETURNING id,").doesNotContain("RETURNINGid");
	}
}
