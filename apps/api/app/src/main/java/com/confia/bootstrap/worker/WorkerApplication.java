package com.confia.bootstrap.worker;

import com.confia.identity.infrastructure.wiring.IdentityWorkerConfiguration;
import com.confia.shared.jobs.infrastructure.wiring.WorkerJobsConfiguration;
import com.confia.shared.jobs.infrastructure.wiring.WorkerPlatformConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Background worker process entry point (ADR-0003, ADR-0013, ADR-0024), started without an HTTP
 * server ({@code ConfiaApplication} forces {@code WebApplicationType.NONE}). It declares its own
 * wiring here and never inherits the administrative bean graph, so it carries none of the OpenAPI
 * surface the web processes import. The only business module it registers is {@code identity},
 * through {@link IdentityWorkerConfiguration}, for the password-reset issuance job (ADR-0016;
 * background-jobs-with-db-scheduler design.md decision 18); the other jobs arrive with the modules
 * that need them.
 *
 * <p>The worker has a {@code DataSource} (background-jobs-with-db-scheduler design.md decision 12,
 * which reverses decision D5 of web-edge-foundations for this process): Spring Boot's pool, with the
 * role {@code confia_admin_app} (owner decision P7) from {@code SPRING_DATASOURCE_URL},
 * {@code SPRING_DATASOURCE_USERNAME} and {@code SPRING_DATASOURCE_PASSWORD}. The pool is lazy, so
 * the process starts with the database down and reports the failure on first use; it never
 * migrates. {@link WorkerPlatformConfiguration} is its whole data access: a {@code DSLContext} with
 * statement logging off, the transactional component, the clock and the plain audit writer. The
 * sensitive log guard is installed by an initializer of {@code ConfiaApplication} before the
 * context creates its first bean, so no bean can log a bound value before the guard exists
 * (condition 4). The worker still has no web edge, no security chain, no master key and no pepper.
 *
 * <p>The auto-configuration of the db-scheduler starter is active here, and it is the only process
 * where it is: the administrative and portal entry points exclude it. The worker declares its own
 * {@code Scheduler} and its own starter in {@link WorkerJobsConfiguration}, and the library's
 * versions of both are conditional on the absence of those, so the context holds exactly one
 * executor and it is ours (probe P-2). Its settings are the {@code db-scheduler.*} properties of
 * {@code application.yml}; {@code spring.main.keep-alive} keeps the JVM running without a web server.
 *
 * <p>{@link SecurityAutoConfiguration} and {@link UserDetailsServiceAutoConfiguration} are
 * excluded (decision 4 of web-edge-foundations): the worker has no web edge, and Spring
 * Security's autoconfiguration would still leave three inert beans in it. An inert bean today is a
 * surface someone wires up tomorrow, and a context with no security bean is the plainest proof
 * that the worker has no security chain.
 *
 * <p>The class is public only so the launcher in the parent package can start it; nothing else
 * may reference it (enforced by ArchUnit).
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class})
@Import({WorkerPlatformConfiguration.class, WorkerJobsConfiguration.class,
        IdentityWorkerConfiguration.class})
public class WorkerApplication {
}
