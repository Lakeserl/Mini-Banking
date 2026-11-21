package com.lakeserl.mini_banking.query.projection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.lakeserl.mini_banking.PostgresDataTest;
import com.lakeserl.mini_banking.deposit.domain.events.DepositConfirmed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositRequested;
import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.wallet.domain.events.WalletCredited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletOpened;

@PostgresDataTest
class QueryProjectorsPostgresTest {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	BalanceProjector balanceProjector;

	@Autowired
	TransactionHistoryProjector txHistoryProjector;

	@BeforeEach
	void clean() {
		jdbc.update("TRUNCATE TABLE wallet_balances, transaction_history, daily_reports, processed_events RESTART IDENTITY");
	}

	@Test
	void projectsBalanceTransactionHistoryAndAsiaHoChiMinhDailyReportIdempotently() {
		UUID walletId = UUID.randomUUID();
		UUID ownerId = UUID.randomUUID();
		UUID depositId = UUID.randomUUID();
		Instant t1 = Instant.parse("2026-11-21T17:30:00Z");

		balanceProjector.project(UUID.randomUUID(), new WalletOpened(walletId, ownerId, Currency.VND), 1L, t1);
		UUID creditEventId = UUID.randomUUID();
		WalletCredited credited = new WalletCredited(walletId, Money.vnd(750_000), depositId, "BANK_SETTLEMENT",
				AccountType.SETTLEMENT);
		assertThat(balanceProjector.project(creditEventId, credited, 2L, t1)).isTrue();
		assertThat(balanceProjector.project(creditEventId, credited, 2L, t1)).isFalse();

		Long balance = jdbc.queryForObject("SELECT balance FROM wallet_balances WHERE wallet_id = ?", Long.class,
				walletId);
		Long version = jdbc.queryForObject("SELECT aggregate_version FROM wallet_balances WHERE wallet_id = ?",
				Long.class, walletId);
		assertThat(balance).isEqualTo(750_000L);
		assertThat(version).isEqualTo(2L);

		txHistoryProjector.project(UUID.randomUUID(),
				new DepositRequested(depositId, walletId, Money.vnd(750_000), "REF-PG-1"), t1);
		txHistoryProjector.project(UUID.randomUUID(), new DepositConfirmed(depositId), t1.plusSeconds(5));

		String status = jdbc.queryForObject("SELECT status FROM transaction_history WHERE transaction_id = ?",
				String.class, depositId);
		Long dailyDeposit = jdbc.queryForObject(
				"SELECT total_deposit FROM daily_reports WHERE report_date = DATE '2026-11-22' AND currency = 'VND'",
				Long.class);
		assertThat(status).isEqualTo("CONFIRMED");
		assertThat(dailyDeposit).isEqualTo(750_000L);
	}
}
