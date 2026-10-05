package com.confia.bootstrap.portal;

import com.confia.shared.web.edge.PortalSecurityConfiguration;
import com.confia.shared.web.edge.WebEdgeConfiguration;
import com.confia.shared.web.openapi.ContractSchemas;
import com.confia.shared.web.openapi.ProcessApiInfo;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Guardian portal process entry point (ADR-0003, ADR-0013, ADR-0024). It declares everything this
 * process loads with {@code @Import}, never with a component scan, so administrative code cannot
 * reach the process exposed to guardians by accident. No business module is registered yet; the
 * web edge is: the request filters, the Problem Details catalog and a security chain that denies
 * every route (web-edge-foundations decisions 4 to 8).
 *
 * <p>Registering a module here is a visible two-line change: its public configuration in the
 * {@code @Import} list and its package in {@code ProcessBeanPolicy}.
 *
 * <p>{@link DataSourceAutoConfiguration} stays excluded (decision D5 of web-edge-foundations): the
 * portal has no database at all, so its compromise cannot reach financial data. Only the
 * administrative process has a {@code DataSource}. {@link UserDetailsServiceAutoConfiguration}
 * and {@link ErrorMvcAutoConfiguration} are excluded for the reasons {@code AdminApplication}
 * gives: no generated password in the log and no {@code /error} route.
 *
 * <p>The class is public only so the launcher in the parent package can start it; nothing else
 * may reference it (enforced by ArchUnit).
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class, ErrorMvcAutoConfiguration.class})
@Import({ContractSchemas.class, ProcessApiInfo.class, WebEdgeConfiguration.class,
        PortalSecurityConfiguration.class})
public class PortalApplication {
}
