package com.lakeserl.mini_banking;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.lakeserl.mini_banking.shared.eventsourcing.EventSerializer;
import com.lakeserl.mini_banking.shared.eventsourcing.EventSourcingConfig;
import com.lakeserl.mini_banking.shared.eventsourcing.EventStoreImmutabilityGuard;
import com.lakeserl.mini_banking.shared.eventsourcing.PostgresEventStore;
import com.lakeserl.mini_banking.shared.eventsourcing.PostgresSnapshotStore;

/**
 * JPA slice against a real PostgreSQL (Testcontainers) with the event sourcing beans.
 * Not wrapped in a test transaction: tests need real commits, locks and unique-constraint failures.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ContextConfiguration(initializers = PostgresContainerInitializer.class)
@TestPropertySource(properties = {
		"spring.jpa.hibernate.ddl-auto=update",
		"spring.datasource.hikari.maximum-pool-size=30" })
@Import({ EventSourcingConfig.class, EventSerializer.class, PostgresEventStore.class,
		PostgresSnapshotStore.class, EventStoreImmutabilityGuard.class,
		com.lakeserl.mini_banking.wallet.infrastructure.WalletEventTypes.class,
		com.lakeserl.mini_banking.wallet.infrastructure.WalletRepository.class,
		com.lakeserl.mini_banking.wallet.command.WalletCommandHandler.class })
public @interface PostgresDataTest {
}
