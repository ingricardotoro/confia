package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.migrate.MigrateApplication;
import com.github.kagkarlsson.scheduler.Scheduler;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The context of the {@code migrate} process is closed (design.md decision 2, scenarios CR03 to
 * CR06): it holds the {@code DataSource} and Flyway and nothing that serves, schedules, secures or
 * measures. {@code migrate} never returns a live context, so the test builds the same one through
 * the launcher's own builder and inspects it before closing it.
 */
class MigrateContextIsolationTest {

    @Test
    void holdsOnlyTheDataSourceAndFlywayAndItsBeansObeyTheMigratePolicy() throws Exception {
        try (ScratchDatabase database = ScratchDatabase.ownedByTheRealOwner();
                ConfigurableApplicationContext context = ConfiaApplication
                        .migrateBuilder().run(database.ownerArguments())) {
            assertThat(context.getBeansOfType(DataSource.class)).as("non-vacuous").hasSize(1);
            assertThat(context.getBeansOfType(Flyway.class)).as("non-vacuous").hasSize(1);
            assertThat(context).as("no web server")
                    .isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(context.getBeanNamesForType(Scheduler.class, true, false))
                    .as("no db-scheduler Scheduler").isEmpty();
            assertThat(context.getBeanNamesForType(SecurityFilterChain.class, true, false))
                    .as("no security chain").isEmpty();
            assertThat(context.getBeanNamesForType(Filter.class, true, false))
                    .as("no request filter").isEmpty();
            assertThat(context.getBeanNamesForType(MeterRegistry.class, true, false))
                    .as("no meter registry").isEmpty();
            assertThat(ProcessBeanInspector.violations(context, ProcessBeanPolicy.MIGRATE))
                    .isEmpty();
            assertThat(ProcessBeanInspector.confiaBeans(context))
                    .as("only the entry point and the sensitive log guard are com.confia beans")
                    .allMatch(bean -> bean.originPackages().stream().allMatch(pkg ->
                            pkg.startsWith(MigrateApplication.class.getPackageName())
                                    || pkg.equals("com.confia.shared.logging")));
        }
    }

    @Test
    void theMigratePolicyForbidsTheSchedulerSecurityAndTheWebEdge() {
        assertThat(ProcessBeanPolicy.MIGRATE.allowedPackages())
                .containsExactlyInAnyOrder("com.confia.bootstrap.migrate", "com.confia.shared.logging");
        assertThat(ProcessBeanPolicy.MIGRATE.forbiddenPackages()).containsOnlyKeys(
                "com.github.kagkarlsson", "org.springframework.security",
                "com.confia.shared.web");
    }
}
