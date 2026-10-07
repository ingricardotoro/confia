package com.confia.bootstrap.admin;

import com.confia.identity.infrastructure.wiring.IdentityConfiguration;
import com.confia.shared.observability.metrics.ObservabilityMetricsConfiguration;
import com.confia.shared.platform.infrastructure.SharedPlatformConfiguration;
import com.confia.shared.security.RateLimiterConfiguration;
import com.confia.shared.security.token.SessionTokenConfiguration;
import com.confia.shared.web.edge.AdminSecurityConfiguration;
import com.confia.shared.web.edge.IdempotencyEdgeConfiguration;
import com.confia.shared.web.edge.RequiredDelayConfiguration;
import com.confia.shared.web.edge.ThrottlingConfiguration;
import com.confia.shared.web.edge.WebEdgeConfiguration;
import com.confia.shared.web.openapi.ContractSchemas;
import com.confia.shared.web.openapi.ProcessApiInfo;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Administrative process entry point (ADR-0003, ADR-0013, ADR-0024). It declares everything this
 * process loads with {@code @Import}, never with a component scan: a class enters this context
 * only if it is written below, so a portal-only or worker-only component cannot arrive by
 * accident. This process registers the shared platform wiring (decision 2 of
 * web-edge-foundations), the {@code identity} module's use cases and secrets (decision 3) and the
 * web edge: the request filters, the Problem Details catalog and the deny-by-default security
 * chain (decisions 4 to 8), the rate limiter at the edge with the adapter of its capacity
 * signal (decision 17), the materializer of the required delay (decision 18), and the HTTP edge of
 * idempotency (decision 19).
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
 * <p>Two autoconfigurations are excluded (decisions 4 and 5). {@link
 * UserDetailsServiceAutoConfiguration} would register an in-memory user and log a generated
 * password, which CLAUDE.md regla 11 forbids and nothing here uses. {@link
 * ErrorMvcAutoConfiguration} would register the {@code /error} controller and its white page, which
 * the chain would have to authorize; without it the route map of the process holds no route that
 * this change did not choose.
 *
 * <p>The class is public only so the launcher in the parent package can start it; nothing else
 * may reference it (enforced by ArchUnit).
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {UserDetailsServiceAutoConfiguration.class,
        ErrorMvcAutoConfiguration.class})
@Import({ContractSchemas.class, ProcessApiInfo.class, SharedPlatformConfiguration.class,
        IdentityConfiguration.class, WebEdgeConfiguration.class,
        AdminSecurityConfiguration.class, ObservabilityMetricsConfiguration.class,
        RateLimiterConfiguration.class, ThrottlingConfiguration.class,
        RequiredDelayConfiguration.class, IdempotencyEdgeConfiguration.class,
        SessionTokenConfiguration.class})
public class AdminApplication {
}
