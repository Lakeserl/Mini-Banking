package com.lakeserl.mini_banking.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.lakeserl.mini_banking.shared.domain.AccountType;
import com.lakeserl.mini_banking.shared.domain.Currency;
import com.lakeserl.mini_banking.shared.domain.CurrencyMismatchException;
import com.lakeserl.mini_banking.shared.domain.DeterministicIds;
import com.lakeserl.mini_banking.shared.domain.Money;
import com.lakeserl.mini_banking.wallet.domain.events.WalletCredited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletOpened;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InsufficientFundsException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.InvalidAmountException;
import com.lakeserl.mini_banking.wallet.domain.exceptions.WalletLockedException;

class WalletTest {

	private UUID walletId;
	private UUID ownerId;

	@BeforeEach
	void setUp() {
		walletId = UUID.randomUUID();
		ownerId = UUID.randomUUID();
	}

	private Wallet newWallet() {
		return Wallet.open(walletId, ownerId, Currency.VND);
	}

	private void credit(Wallet wallet, long amount) {
		wallet.credit(UUID.randomUUID(), Money.vnd(amount), "BANK_SETTLEMENT", AccountType.SETTLEMENT);
	}

	@Test
	void newlyOpenedWalletHasZeroBalanceAndActiveStatus() {
		Wallet wallet = newWallet();
		assertThat(wallet.balance()).isEqualTo(Money.vnd(0));
		assertThat(wallet.status()).isEqualTo(WalletStatus.ACTIVE);
		assertThat(wallet.id()).isEqualTo(walletId);
		assertThat(wallet.ownerId()).isEqualTo(ownerId);
		assertThat(wallet.currency()).isEqualTo(Currency.VND);
		assertThat(wallet.uncommittedEvents()).hasSize(1);
		assertThat(wallet.uncommittedEvents().get(0).event()).isInstanceOf(WalletOpened.class);
	}

	@Test
	void cannotOpenWalletWithCurrencyOtherThanVnd() {
		assertThatThrownBy(() -> Wallet.open(walletId, ownerId, Currency.USD))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void creditIncreasesBalance() {
		Wallet wallet = newWallet();
		credit(wallet, 100);
		assertThat(wallet.balance()).isEqualTo(Money.vnd(100));
		credit(wallet, 50);
		assertThat(wallet.balance()).isEqualTo(Money.vnd(150));
	}

	@Test
	void debitDecreasesBalance() {
		Wallet wallet = newWallet();
		credit(wallet, 100);
		wallet.debit(UUID.randomUUID(), Money.vnd(40), "TRANSFER_CLEARING", AccountType.CLEARING);
		assertThat(wallet.balance()).isEqualTo(Money.vnd(60));
	}

	@Test
	void debitMoreThanBalanceThrowsInsufficientFunds() {
		Wallet wallet = newWallet();
		credit(wallet, 50);
		assertThatThrownBy(
				() -> wallet.debit(UUID.randomUUID(), Money.vnd(51), "TRANSFER_CLEARING", AccountType.CLEARING))
				.isInstanceOf(InsufficientFundsException.class);
		assertThat(wallet.balance()).isEqualTo(Money.vnd(50));
	}

	@Test
	void zeroOrNegativeAmountIsRejectedOnCreditAndDebit() {
		Wallet wallet = newWallet();
		assertThatThrownBy(() -> credit(wallet, 0)).isInstanceOf(InvalidAmountException.class);
		assertThatThrownBy(() -> credit(wallet, -10)).isInstanceOf(InvalidAmountException.class);
		assertThatThrownBy(() -> wallet.debit(UUID.randomUUID(), Money.vnd(0), "X", AccountType.CLEARING))
				.isInstanceOf(InvalidAmountException.class);
	}

	@Test
	void lockedWalletRejectsCreditAndDebit() {
		Wallet wallet = newWallet();
		credit(wallet, 100);
		wallet.lock("suspicious activity");
		assertThat(wallet.status()).isEqualTo(WalletStatus.LOCKED);

		assertThatThrownBy(() -> credit(wallet, 50)).isInstanceOf(WalletLockedException.class);
		assertThatThrownBy(() -> wallet.debit(UUID.randomUUID(), Money.vnd(50), "X", AccountType.CLEARING))
				.isInstanceOf(WalletLockedException.class);
	}

	@Test
	void lockingAnAlreadyLockedWalletDoesNotRaiseNewEvents() {
		Wallet wallet = newWallet();
		wallet.lock("first");
		int count = wallet.uncommittedEvents().size();
		wallet.lock("second");
		assertThat(wallet.uncommittedEvents()).hasSize(count);
	}

	@Test
	void creditWithWrongCurrencyIsRejected() {
		Wallet wallet = newWallet();
		assertThatThrownBy(() -> wallet.credit(UUID.randomUUID(), new Money(10, Currency.USD), "X", AccountType.SETTLEMENT))
				.isInstanceOf(CurrencyMismatchException.class);
	}

	@Test
	void balanceOverflowIsRejectedInsteadOfWrappingAround() {
		Wallet wallet = newWallet();
		credit(wallet, Long.MAX_VALUE);
		assertThatThrownBy(() -> credit(wallet, 1)).isInstanceOf(ArithmeticException.class);
		assertThat(wallet.balance()).isEqualTo(Money.vnd(Long.MAX_VALUE));
	}

	@Test
	void sameTransactionAndWalletGiveTheSameEventId() {
		UUID tx = UUID.randomUUID();
		assertThat(Wallet.debitEventId(tx, walletId)).isEqualTo(Wallet.debitEventId(tx, walletId));
		assertThat(Wallet.debitEventId(tx, walletId)).isNotEqualTo(Wallet.creditEventId(tx, walletId));
		assertThat(Wallet.debitEventId(tx, walletId)).isNotEqualTo(Wallet.debitEventId(UUID.randomUUID(), walletId));
		assertThat(Wallet.debitEventId(tx, walletId).version()).isEqualTo(5);
	}

	@Test
	void deterministicIdsMatchUuidV5Rfc4122FixedVector() {
		// RFC 4122 Test Vector: DNS namespace + "python.org" = 886313e1-3b8a-5372-9b90-0c9aee199e5d
		UUID dnsNamespace = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
		UUID result = DeterministicIds.v5(dnsNamespace, "python.org");
		assertThat(result).isEqualTo(UUID.fromString("886313e1-3b8a-5372-9b90-0c9aee199e5d"));
		assertThat(result.version()).isEqualTo(5);
		assertThat(result.variant()).isEqualTo(2); // Leach-Salz / RFC 4122
	}

	@Test
	void eventIdOnRaisedEventIsTheDeterministicOne() {
		Wallet wallet = newWallet();
		credit(wallet, 100);
		UUID tx = UUID.randomUUID();
		wallet.debit(tx, Money.vnd(10), "X", AccountType.SETTLEMENT);
		assertThat(wallet.uncommittedEvents().get(2).eventId()).isEqualTo(Wallet.debitEventId(tx, walletId));
	}

	@Test
	void replayRebuildsSameStateAndVersion() {
		Wallet wallet = newWallet();
		credit(wallet, 300);
		wallet.debit(UUID.randomUUID(), Money.vnd(100), "X", AccountType.CLEARING);
		Wallet replayed = Wallet.fromHistory(wallet.uncommittedEvents().stream().map(p -> p.event()).toList());
		assertThat(replayed.balance()).isEqualTo(wallet.balance());
		assertThat(replayed.status()).isEqualTo(wallet.status());
		assertThat(replayed.committedVersion()).isEqualTo(wallet.version());
		assertThat(replayed.uncommittedEvents()).isEmpty();
	}

	@Test
	void replayRejectsEventWithMismatchedWalletId() {
		Wallet wallet = newWallet();
		UUID otherWalletId = UUID.randomUUID();
		WalletCredited mismatchedEvent = new WalletCredited(otherWalletId, Money.vnd(100), UUID.randomUUID(), "X",
				AccountType.SETTLEMENT);
		assertThatThrownBy(() -> wallet.loadFromHistory(java.util.List.of(mismatchedEvent)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("does not match wallet");
	}

	@Test
	void operationsRejectNullParameters() {
		Wallet wallet = newWallet();
		UUID tx = UUID.randomUUID();
		Money amount = Money.vnd(100);

		assertThatThrownBy(() -> Wallet.open(null, ownerId, Currency.VND)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Wallet.open(walletId, null, Currency.VND)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Wallet.open(walletId, ownerId, null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Wallet.calculateWalletId(null, Currency.VND)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> Wallet.calculateWalletId(ownerId, null)).isInstanceOf(NullPointerException.class);

		assertThatThrownBy(() -> wallet.credit(null, amount, "X", AccountType.SETTLEMENT)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> wallet.credit(tx, null, "X", AccountType.SETTLEMENT)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> wallet.credit(tx, amount, null, AccountType.SETTLEMENT)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> wallet.credit(tx, amount, "X", null)).isInstanceOf(NullPointerException.class);

		assertThatThrownBy(() -> wallet.debit(null, amount, "X", AccountType.CLEARING)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> wallet.lock(null)).isInstanceOf(NullPointerException.class);
	}
}
