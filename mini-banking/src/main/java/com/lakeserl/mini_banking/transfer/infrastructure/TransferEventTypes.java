package com.lakeserl.mini_banking.transfer.infrastructure;

import java.util.List;

import org.springframework.stereotype.Component;

import com.lakeserl.mini_banking.shared.domain.DomainEvent;
import com.lakeserl.mini_banking.shared.eventsourcing.EventTypeProvider;
import com.lakeserl.mini_banking.transfer.domain.events.TransferCompensated;
import com.lakeserl.mini_banking.transfer.domain.events.TransferCompensationStarted;
import com.lakeserl.mini_banking.transfer.domain.events.TransferCompleted;
import com.lakeserl.mini_banking.transfer.domain.events.TransferCreditCompleted;
import com.lakeserl.mini_banking.transfer.domain.events.TransferDebitCompleted;
import com.lakeserl.mini_banking.transfer.domain.events.TransferFailed;
import com.lakeserl.mini_banking.transfer.domain.events.TransferInitiated;

@Component
public class TransferEventTypes implements EventTypeProvider {

	@Override
	public List<Class<? extends DomainEvent>> eventTypes() {
		return List.of(
				TransferInitiated.class,
				TransferDebitCompleted.class,
				TransferCreditCompleted.class,
				TransferCompleted.class,
				TransferFailed.class,
				TransferCompensationStarted.class,
				TransferCompensated.class);
	}
}
