package com.alejandromacias.betflow.wallet.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A real Postgres for every test that touches the schema.
 *
 * <p>Nothing here would survive an in-memory stand-in: a row lock deciding which of two writers
 * waits, a version check counting affected rows, and a guarded update reporting whether it fired.
 *
 * <p>A near-copy of betting-service's class of the same name, and deliberately not shared. A
 * common test module would be a build dependency between two bounded contexts for the sake of
 * twenty lines — the same trade refused for the event schema in ADR-003, and the same answer.
 * Worth revisiting if a third service needs it.
 */
public abstract class PostgresBackedTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
