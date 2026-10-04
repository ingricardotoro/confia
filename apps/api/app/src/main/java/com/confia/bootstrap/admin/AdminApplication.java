package com.confia.bootstrap.admin;

import com.confia.shared.web.openapi.ContractSchemas;
import com.confia.shared.web.openapi.ProcessApiInfo;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Administrative process entry point (ADR-0003, ADR-0013, ADR-0024). It declares everything this
 * process loads with {@code @Import}, never with a component scan: a class enters this context
 * only if it is written below, so a portal-only or worker-only component cannot arrive by
 * accident. No business module is registered yet.
 *
 * <p>Registering a module here is a visible two-line change: its public configuration in the
 * {@code @Import} list and its package in {@code ProcessBeanPolicy}, which the isolation test
 * checks against the started context.
 *
 * <p>{@link DataSourceAutoConfiguration} is excluded because no {@code spring.datasource.*}
 * property exists yet in this process's configuration: nothing here consumes a {@code DataSource}
 * or a {@code DSLContext} bean today (design.md decision 8 of jooq-flyway-testcontainers-wiring).
 * Without this exclusion Spring Boot tries to build a Hikari pool with no JDBC URL and this
 * process fails to start. The exclusion is removed by whichever change first registers a real
 * production {@code DataSource}.
 *
 * <p>The class is public only so the launcher in the parent package can start it; nothing else
 * may reference it (enforced by ArchUnit).
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
@Import({ContractSchemas.class, ProcessApiInfo.class})
public class AdminApplication {
}
