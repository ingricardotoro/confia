package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import com.confia.shared.jobs.JobScheduler;
import com.confia.shared.security.TransactionRunner;
import com.confia.shared.security.TransactionalDataSource;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Specs/build-integrity of background-jobs-with-db-scheduler (BJ51, BI112, BI116; design.md
 * decision 14, D-N1): the portal process builds its context with a database nothing listens on and
 * holds the narrow client the portal needs to schedule a job, and nothing that reads or writes
 * business data. Started through the production launcher with {@link TestProcessArguments}, so no
 * container is needed and this stays a {@code *Test}.
 */
class PortalContextStartupTest {

    @Test
    void thePortalStartsWithAnUnreachableDatabaseAndItsPoolHasNotConnected() throws SQLException {
        ConfigurableApplicationContext context = startPortal();
        try {
            HikariDataSource pool = context.getBean(DataSource.class).unwrap(HikariDataSource.class);
            assertThat(pool.getHikariPoolMXBean())
                    .as("a pool that has started would have a MXBean: the start must be lazy")
                    .isNull();
        } finally {
            context.close();
        }
    }

    @Test
    void thePortalHoldsTheSchedulingClientAndNoBusinessDataAccess() {
        ConfigurableApplicationContext context = startPortal();
        try {
            assertThat(context.getBeansOfType(DataSource.class)).as("the portal's own data source")
                    .hasSize(1);
            assertThat(context.getBeansOfType(TransactionalDataSource.class)).hasSize(1);
            assertThat(context.getBeansOfType(TransactionRunner.class)).hasSize(1);
            assertThat(context.getBeansOfType(JobScheduler.class)).hasSize(1);
            assertThat(context.getBeansOfType(DSLContext.class))
                    .as("the portal has no jOOQ context: it cannot read or write business data")
                    .isEmpty();
        } finally {
            context.close();
        }
    }

    @Test
    void thePortalContainsNoBeanOfIdentityOrOrganizationOrAnyAdministrativeWiring() {
        ConfigurableApplicationContext context = startPortal();
        try {
            List<ProcessBeanInspector.InspectedBean> beans = ProcessBeanInspector.confiaBeans(context);
            assertThat(beans).as("non-vacuous: the portal holds beans of its own").isNotEmpty();
            for (String forbidden : List.of("com.confia.identity", "com.confia.organization",
                    "com.confia.shared.platform", "com.confia.shared.crypto",
                    "com.confia.shared.audit", "com.confia.shared.infrastructure")) {
                assertThat(beans).as("no bean of %s in the portal", forbidden)
                        .noneMatch(bean -> bean.originPackages().stream()
                                .anyMatch(pkg -> ProcessBeanPolicy.matches(pkg, forbidden)));
            }
        } finally {
            context.close();
        }
    }

    private static ConfigurableApplicationContext startPortal() {
        LaunchOutcome outcome = ConfiaApplication.launch(TestProcessArguments.forProcess("portal"),
                "portal");
        assertThat(outcome.context()).as("the portal process must start").isNotNull();
        return outcome.context();
    }
}
