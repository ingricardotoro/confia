package com.confia.bootstrap.portal;

import com.confia.shared.web.openapi.ContractSchemas;
import com.confia.shared.web.openapi.ProcessApiInfo;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Guardian portal process entry point (ADR-0003, ADR-0013, ADR-0024). It declares everything this
 * process loads with {@code @Import}, never with a component scan, so administrative code cannot
 * reach the process exposed to guardians by accident. No business module is registered yet.
 *
 * <p>Registering a module here is a visible two-line change: its public configuration in the
 * {@code @Import} list and its package in {@code ProcessBeanPolicy}.
 *
 * <p>{@link DataSourceAutoConfiguration} stays excluded (decision D5 of web-edge-foundations): the
 * portal has no database at all, so its compromise cannot reach financial data. Only the
 * administrative process has a {@code DataSource}.
 *
 * <p>The class is public only so the launcher in the parent package can start it; nothing else
 * may reference it (enforced by ArchUnit).
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
@Import({ContractSchemas.class, ProcessApiInfo.class})
public class PortalApplication {
}
