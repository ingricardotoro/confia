package com.confia.bootstrap.worker;

import com.confia.identity.infrastructure.wiring.IdentityWorkerConfiguration;
import com.confia.shared.infrastructure.wiring.SharedWorkerJobsConfiguration;
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
 * <p>Two guards act before the context exists, both attached by {@code ConfiaApplication} to the
 * builder of this process: the {@code SensitiveLogGuardInitializer} described above, and a {@code
 * SigningKeyPlacementGuard} listener that refuses to start the worker with the administrative
 * signing private key in its environment (ADR-0005, check 14). A third check runs at startup: the
 * property {@code confia.jobs.institutions} ({@code CONFIA_JOBS_INSTITUTIONS}) is mandatory, so a
 * missing, empty, repeated or malformed list of institutions stops the process before it starts any
 * job, with a message that names the property and the position of the entry, never the list. The
 * recurring jobs run once for each institution on that list; discovering the institutions from the
 * database is the debt of phase 2.
 *
 * <p>The wiring is narrow on purpose. Besides {@link WorkerPlatformConfiguration} and {@link
 * WorkerJobsConfiguration}, the context holds only {@link IdentityWorkerConfiguration} and {@link
 * SharedWorkerJobsConfiguration}, which declare the jobs of the catalog (the five retention purges,
 * the two cadences of the audit chain verification and the observation of the queue). Which packages
 * and beans the worker may hold is a closed allow-list, the {@code WORKER} entry of {@code
 * ProcessBeanPolicy}, which {@code ProcessBeanIsolationTest} checks against the real context: a new
 * bean in this process is a decision that changes that list, never an accident.
 *
 * <p>The class is public only so the launcher in the parent package can start it; nothing else
 * may reference it (enforced by ArchUnit).
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {SecurityAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class}, excludeName = {
        "org.springframework.boot.servlet.autoconfigure.MultipartAutoConfiguration"})
@Import({WorkerPlatformConfiguration.class, WorkerJobsConfiguration.class,
        IdentityWorkerConfiguration.class, SharedWorkerJobsConfiguration.class})
public class WorkerApplication {
}
