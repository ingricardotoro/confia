package com.confia.bootstrap;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * Background worker process entry point (ADR-0003, ADR-0013), started without an HTTP server
 * ({@link ConfiaApplication} forces {@code WebApplicationType.NONE}). {@code scanBasePackages}
 * stays explicit; no business module or background job is registered yet (db-scheduler tasks
 * arrive with the modules that need them, ADR-0016).
 *
 * <p>{@link DataSourceAutoConfiguration} is excluded for the same reason as {@code
 * AdminApplication}: no {@code spring.datasource.*} property exists yet, and nothing here
 * consumes a {@code DataSource} bean today.
 */
@SpringBootApplication(scanBasePackages = "com.confia.bootstrap",
        exclude = DataSourceAutoConfiguration.class)
class WorkerApplication {
}
