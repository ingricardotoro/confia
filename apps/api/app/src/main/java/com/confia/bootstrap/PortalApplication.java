package com.confia.bootstrap;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * Guardian- and student-facing portal process entry point (ADR-0003, ADR-0013). {@code
 * scanBasePackages} stays explicit so this process never auto-discovers an administrative
 * component by accident; no business module is registered yet.
 *
 * <p><strong>An administrative module must never be scanned here.</strong> That barrier is what
 * guarantees invoicing, cashbox, administrative users and the full audit trail are not even
 * loaded in memory in the process exposed to the open internet.
 *
 * <p>{@link DataSourceAutoConfiguration} is excluded for the same reason as {@code
 * AdminApplication}: no {@code spring.datasource.*} property exists yet, and nothing here
 * consumes a {@code DataSource} bean today.
 */
@SpringBootApplication(scanBasePackages = "com.confia.bootstrap",
        exclude = DataSourceAutoConfiguration.class)
class PortalApplication {
}
