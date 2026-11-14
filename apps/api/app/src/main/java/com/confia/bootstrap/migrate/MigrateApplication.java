package com.confia.bootstrap.migrate;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * Entry point of the {@code migrate} process (dockerfiles-and-local-compose design.md decision 2):
 * a closed context that applies the pending Flyway migrations during its refresh and is closed by
 * the launcher right after.
 *
 * <p>It imports exactly two auto-configurations, the {@code DataSource} and Flyway, and nothing
 * else: no {@code @EnableAutoConfiguration} and no import of any {@code com.confia} configuration.
 * What is not in that list does not exist in this context, so it can open no port, create no
 * security chain, register no health indicator or meter registry and, above all, start no
 * db-scheduler {@code Scheduler}, which would begin to run tasks the moment a {@code DataSource}
 * is present. A closed list, not an exclusion list: a starter added to the project tomorrow stays
 * out of this process by default (same principle as ADR-0024).
 *
 * <p>The class is public only so the launcher in the parent package can start it; nothing else may
 * reference it (enforced by ArchUnit).
 */
@SpringBootConfiguration
@ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
public class MigrateApplication {
}
