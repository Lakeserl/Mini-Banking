package com.lakeserl.mini_banking;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Starts one PostgreSQL container for the whole test run and points the datasource at it. */
public class PostgresContainerInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

	static {
		POSTGRES.start(); // stopped by Testcontainers' Ryuk when the JVM exits
	}

	@Override
	public void initialize(ConfigurableApplicationContext context) {
		TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
				"spring.datasource.url=" + POSTGRES.getJdbcUrl(),
				"spring.datasource.username=" + POSTGRES.getUsername(),
				"spring.datasource.password=" + POSTGRES.getPassword());
	}
}
