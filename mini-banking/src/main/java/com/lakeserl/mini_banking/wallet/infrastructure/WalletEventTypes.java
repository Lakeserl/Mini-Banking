package com.lakeserl.mini_banking.wallet.infrastructure;

import java.util.List;

import org.springframework.stereotype.Component;

import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.eventsourcing.EventTypeProvider;
import com.lakeserl.mini_banking.wallet.domain.events.WalletCredited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletDebited;
import com.lakeserl.mini_banking.wallet.domain.events.WalletLocked;
import com.lakeserl.mini_banking.wallet.domain.events.WalletOpened;

@Component
public class WalletEventTypes implements EventTypeProvider {

	@Override
	public List<Class<? extends DomainEvent>> eventTypes() {
		return List.of(WalletOpened.class, WalletCredited.class, WalletDebited.class, WalletLocked.class);
	}
}
