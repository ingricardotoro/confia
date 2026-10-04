package com.confia.bootstrap.admin;

import com.confia.shared.platform.infrastructure.SharedPlatformConfiguration;
import com.confia.shared.web.openapi.ContractSchemas;
import com.confia.shared.web.openapi.ProcessApiInfo;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Administrative process entry point (ADR-0003, ADR-0013, ADR-0024). It declares everything this
 * process loads with {@code @Import}, never with a component scan: a class enters this context
 * only if it is written below, so a portal-only or worker-only component cannot arrive by
 * accident. This process registers the shared platform wiring (decision 2 of
 * web-edge-foundations); no business module is registered yet.
 *
 * <p>Registering a module here is a visible two-line change: its public configuration in the
 * {@code @Import} list and its package in {@code ProcessBeanPolicy}, which the isolation test
 * checks against the started context.
 *
 * <p>This is the only process with a {@code DataSource} (decision D5 of web-edge-foundations): the
 * portal and the worker keep excluding {@code DataSourceAutoConfiguration}. The JDBC URL comes from
 * {@code SPRING_DATASOURCE_URL} and the pool connects lazily, so this process starts without a
 * reachable database and never migrates at startup.
 *
 * <p>The class is public only so the launcher in the parent package can start it; nothing else
 * may reference it (enforced by ArchUnit).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({ContractSchemas.class, ProcessApiInfo.class, SharedPlatformConfiguration.class})
public class AdminApplication {
}
