package com.lakeserl.mini_banking.query.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class QueryRepositoryUnitTest {

	@Test
	@SuppressWarnings("unchecked")
	void findBalancesByOwnerQueriesByOwnerIdOrderedDeterministicallyAndRejectsNull() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		QueryRepository repo = new QueryRepository(jdbc);
		UUID ownerId = UUID.randomUUID();

		when(jdbc.query(anyString(), any(RowMapper.class), eq(ownerId))).thenReturn(List.of());

		assertThat(repo.findBalancesByOwner(ownerId)).isEmpty();
		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(ownerId));
		assertThat(sql.getValue())
				.contains("FROM wallet_balances")
				.contains("WHERE owner_id = ?")
				.contains("ORDER BY currency ASC, wallet_id ASC");

		assertThatThrownBy(() -> repo.findBalancesByOwner(null))
				.isInstanceOf(NullPointerException.class);
	}
}
