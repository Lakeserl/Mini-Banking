package com.lakeserl.mini_banking.deposit.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.lakeserl.mini_banking.deposit.domain.exceptions.DuplicateBankReferenceException;
import com.lakeserl.mini_banking.shared.domain.Money;

class DepositBankReferenceStoreUnitTest {

	@Test
	void tryClaimReturnsTrueOnInsertAndUsesOnConflictDepositIdDoNothing() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
		DepositBankReferenceStore store = new DepositBankReferenceStore(jdbc);

		UUID depositId = UUID.randomUUID();
		UUID walletId = UUID.randomUUID();
		assertThat(store.tryClaim(depositId, "REF-CLAIM-1", walletId, Money.vnd(250_000))).isTrue();

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbc).update(sqlCaptor.capture(), any(), any(), any(), any(), any());
		assertThat(sqlCaptor.getValue()).contains("ON CONFLICT (deposit_id) DO NOTHING");
	}

	@Test
	void tryClaimReturnsFalseWhenSameDepositIdAlreadyClaimed() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(0);
		DepositBankReferenceStore store = new DepositBankReferenceStore(jdbc);

		assertThat(store.tryClaim(UUID.randomUUID(), "REF-CLAIM-2", UUID.randomUUID(), Money.vnd(100_000))).isFalse();
	}

	@Test
	void tryClaimTranslatesUpperBankReferenceUniqueViolationToNeutralDuplicateBankReferenceException() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		PSQLException psqlEx = new PSQLException(new ServerErrorMessage(
				"SERROR\0VERROR\0C23505\0Mduplicate key value violates unique constraint\0nuq_deposit_bank_reference_upper\0"));
		when(jdbc.update(anyString(), any(), any(), any(), any(), any()))
				.thenThrow(new DuplicateKeyException("dup", psqlEx));
		DepositBankReferenceStore store = new DepositBankReferenceStore(jdbc);

		assertThatThrownBy(() -> store.tryClaim(UUID.randomUUID(), "ref-dup-99", UUID.randomUUID(), Money.vnd(50_000)))
				.isInstanceOf(DuplicateBankReferenceException.class)
				.hasMessage("Bank reference has already been used")
				.satisfies(ex -> assertThat(((DuplicateBankReferenceException) ex).bankReference())
						.isEqualTo("ref-dup-99"));
	}

	@Test
	void tryClaimRethrowsDuplicateKeyExceptionForUnknownConstraint() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		PSQLException psqlEx = new PSQLException(new ServerErrorMessage(
				"SERROR\0VERROR\0C23505\0Mduplicate key value\0nsome_other_constraint\0"));
		DuplicateKeyException original = new DuplicateKeyException("other dup", psqlEx);
		when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenThrow(original);
		DepositBankReferenceStore store = new DepositBankReferenceStore(jdbc);

		assertThatThrownBy(() -> store.tryClaim(UUID.randomUUID(), "REF-OTHER", UUID.randomUUID(), Money.vnd(50_000)))
				.isSameAs(original);
	}

	@Test
	void tryClaimRequiresMandatoryTransactionPropagation() throws NoSuchMethodException {
		Method method = DepositBankReferenceStore.class.getMethod("tryClaim",
				UUID.class, String.class, UUID.class, Money.class);
		Transactional tx = method.getAnnotation(Transactional.class);
		assertThat(tx).isNotNull();
		assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
	}
}
