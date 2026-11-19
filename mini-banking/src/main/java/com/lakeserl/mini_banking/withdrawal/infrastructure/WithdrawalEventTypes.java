package com.lakeserl.mini_banking.withdrawal.infrastructure;

import java.util.List;

import org.springframework.stereotype.Component;

import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.eventsourcing.EventTypeProvider;
import com.lakeserl.mini_banking.withdrawal.domain.events.WithdrawalConfirmed;
import com.lakeserl.mini_banking.withdrawal.domain.events.WithdrawalFailed;
import com.lakeserl.mini_banking.withdrawal.domain.events.WithdrawalRequested;

@Component
public class WithdrawalEventTypes implements EventTypeProvider {

	@Override
	public List<Class<? extends DomainEvent>> eventTypes() {
		return List.of(WithdrawalRequested.class, WithdrawalConfirmed.class, WithdrawalFailed.class);
	}
}
