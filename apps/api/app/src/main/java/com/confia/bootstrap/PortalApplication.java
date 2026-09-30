package com.confia.bootstrap;

import com.confia.shared.web.openapi.ContractSchemas;
import com.confia.shared.web.openapi.ProcessApiInfo;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Import;

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
 *
 * <p>{@link ContractSchemas} and {@link ProcessApiInfo} are imported explicitly, never scanned:
 * {@code scanBasePackages} stays {@code com.confia.bootstrap} (frontend-monorepo-and-contracts-
 * pipeline, design.md decision 4).
 */
@SpringBootApplication(scanBasePackages = "com.confia.bootstrap",
        exclude = DataSourceAutoConfiguration.class)
@Import({ContractSchemas.class, ProcessApiInfo.class})
class PortalApplication {
}
