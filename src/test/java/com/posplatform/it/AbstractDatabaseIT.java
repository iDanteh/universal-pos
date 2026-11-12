package com.posplatform.it;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared PostgreSQL container for all integration tests.
 * The runtime datasource uses the RESTRICTED application user (not owner,
 * no BYPASSRLS) and Flyway uses the migrator role - mirroring production.
 * Row Level Security is therefore enforced in every test by default.
 *
 * The container is started ONCE per test JVM (static initializer, NOT the
 * per-class @Container lifecycle): an inherited static @Container restarts
 * (and thus re-maps ports) after EVERY test class, which kills cached Spring
 * contexts of later classes with "Connection refused". Ryuk discards the
 * container when the JVM exits.
 */
@SpringBootTest
public abstract class AbstractDatabaseIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withInitScript("sql/it-roles-init.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "posplatform_app");
        registry.add("spring.datasource.password", () -> "it-app");

        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "posplatform_migrator");
        registry.add("spring.flyway.password", () -> "it-migrator");
    }
}
