package com.confia.bootstrap.portal;

import com.confia.shared.jobs.infrastructure.wiring.JobSchedulingConfiguration;
import com.confia.shared.security.TransactionRunnerConfiguration;
import com.confia.shared.web.edge.PortalSecurityConfiguration;
import com.confia.shared.web.edge.WebEdgeConfiguration;
import com.confia.shared.web.openapi.ContractSchemas;
import com.confia.shared.web.openapi.ProcessApiInfo;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Guardian portal process entry point (ADR-0003, ADR-0013, ADR-0024). It declares everything this
 * process loads with {@code @Import}, never with a component scan, so administrative code cannot
 * reach the process exposed to guardians by accident. No business module is registered yet; the
 * web edge is: the request filters, the Problem Details catalog and a security chain that denies
 * every route (web-edge-foundations decisions 4 to 8), and the narrow scheduling client below.
 *
 * <p>Registering a module here is a visible two-line change: its public configuration in the
 * {@code @Import} list and its package in {@code ProcessBeanPolicy}.
 *
 * <p>The portal has its own {@code DataSource} since background-jobs-with-db-scheduler (decision
 * 14, D-N1): {@code DataSourceAutoConfiguration} is no longer excluded, and the process connects as
 * {@code confia_portal_app}, whose only privileges on {@code scheduled_tasks} are {@code SELECT} and
 * {@code INSERT} (V10). It registers exactly two configurations for it,
 * {@link TransactionRunnerConfiguration} (the one transactional component and its proxy) and
 * {@link JobSchedulingConfiguration} (the {@code JobScheduler} port and its serializer): no
 * {@code DSLContext}, no repository, no business module and none of the administrative platform
 * wiring, so the portal can schedule a job but cannot read or write business data. Its pool is
 * lazy, so the process starts with the database down. {@link UserDetailsServiceAutoConfiguration}
 * and {@link ErrorMvcAutoConfiguration} stay excluded for the reasons {@code AdminApplication}
 * gives: no generated password in the log and no {@code /error} route.
 *
 * <p>The three auto-configurations of the db-scheduler starter are excluded by name
 * (background-jobs-with-db-scheduler decision 4): the portal now has a {@code DataSource}, so the
 * exclusion is what keeps it from building a {@code Scheduler}; only the worker runs jobs
 * (ADR-0016). {@code StarterAutoConfigurationExcludedTest} fails the build naming any starter entry
 * missing here.
 *
 * <p>The class is public only so the launcher in the parent package can start it; nothing else
 * may reference it (enforced by ArchUnit).
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {UserDetailsServiceAutoConfiguration.class,
        ErrorMvcAutoConfiguration.class}, excludeName = {
        "com.github.kagkarlsson.scheduler.boot.autoconfigure.DbSchedulerAutoConfiguration",
        "com.github.kagkarlsson.scheduler.boot.autoconfigure.DbSchedulerMetricsAutoConfiguration",
        "com.github.kagkarlsson.scheduler.boot.autoconfigure.DbSchedulerActuatorAutoConfiguration"})
@Import({ContractSchemas.class, ProcessApiInfo.class, WebEdgeConfiguration.class,
        PortalSecurityConfiguration.class, TransactionRunnerConfiguration.class,
        JobSchedulingConfiguration.class})
public class PortalApplication {
}
