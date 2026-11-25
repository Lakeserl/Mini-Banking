package com.lakeserl.mini_banking.deposit.infrastructure;

import java.util.List;

import org.springframework.stereotype.Component;

import com.lakeserl.mini_banking.deposit.domain.events.DepositBankVerified;
import com.lakeserl.mini_banking.deposit.domain.events.DepositConfirmed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositFailed;
import com.lakeserl.mini_banking.deposit.domain.events.DepositRequested;
import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.eventsourcing.EventTypeProvider;

@Component
public class DepositEventTypes implements EventTypeProvider {

	@Override
	public List<Class<? extends DomainEvent>> eventTypes() {
		return List.of(DepositRequested.class, DepositBankVerified.class, DepositConfirmed.class, DepositFailed.class);
	}
}
