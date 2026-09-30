package com.confia.bootstrap;

import com.confia.shared.web.openapi.ContractSchemas;
import com.confia.shared.web.openapi.ProcessApiInfo;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Administrative process entry point (ADR-0003, ADR-0013). {@code scanBasePackages} stays
 * explicit so this process never auto-discovers a portal-only or worker-only component by
 * accident; no business module is registered yet.
 *
 * <p><strong>A module built for the portal must never be scanned here, and vice versa.</strong>
 * That separation is what keeps administrative code out of the process exposed to guardians.
 *
 * <p>{@link DataSourceAutoConfiguration} is excluded because no {@code spring.datasource.*}
 * property exists yet in this process's configuration: nothing here consumes a {@code DataSource}
 * or a {@code DSLContext} bean today (design.md decision 8 of jooq-flyway-testcontainers-wiring —
 * {@code JooqInstitutionRepository} takes an explicit constructor argument, never a Spring bean).
 * Without this exclusion Spring Boot tries to build a Hikari pool with no JDBC URL and this
 * process fails to start. The exclusion is removed by whichever change first registers a real
 * production {@code DataSource}.
 *
 * <p>{@link ContractSchemas} and {@link ProcessApiInfo} are imported explicitly, never scanned:
 * {@code scanBasePackages} stays {@code com.confia.bootstrap} (frontend-monorepo-and-contracts-
 * pipeline, design.md decision 4).
 */
@SpringBootApplication(scanBasePackages = "com.confia.bootstrap",
        exclude = DataSourceAutoConfiguration.class)
@Import({ContractSchemas.class, ProcessApiInfo.class})
class AdminApplication {
}
