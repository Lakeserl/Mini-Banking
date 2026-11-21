package com.lakeserl.mini_banking;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest(properties = {
		"outbox.relay.enabled=false",
		"transfer.recovery.enabled=false",
		"ledger.consumer.enabled=false",
		"query.consumer.enabled=false",
		"security.jwt.secret=test-only-jwt-secret-key-at-least-32-bytes-long" })
@ContextConfiguration(initializers = PostgresContainerInitializer.class)
class MiniBankingApplicationTests {

	@Test
	void contextLoads() {
	}

}
