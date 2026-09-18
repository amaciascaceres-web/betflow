package com.alejandromacias.betflow.betting.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A real Postgres for tests that touch the projection.
 *
 * <p>Day 3's consumer could be tested with the datasource switched off, because its effect was a
 * log line. Once the effect is a row, that exclusion stops being honest: what is under test is
 * {@code ON CONFLICT} resolving a collision and a timestamp comparison deciding a winner, and
 * neither is worth verifying against a database that merely resembles Postgres.
 *
 * <p>The container is static and started once for the whole JVM rather than per class, so several
 * test classes share one database instead of paying for one each. It is never stopped on purpose:
 * Testcontainers' reaper removes it when the JVM exits.
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
