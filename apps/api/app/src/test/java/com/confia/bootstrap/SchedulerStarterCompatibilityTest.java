package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import com.confia.shared.jobs.infrastructure.WorkerSchedulerStarter;
import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerStarter;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Probe P-2 and scenarios BJ47 and BI95 (background-jobs-with-db-scheduler design.md decision 1): the
 * db-scheduler starter is on the class path and its auto-configuration is active in the worker, but
 * the worker's own {@code Scheduler} and its own starter displace the ones the library would declare
 * ({@code @ConditionalOnMissingBean}), so the context holds exactly one executor and it is ours.
 */
class SchedulerStarterCompatibilityTest {

    @Test
    void theWorkerHoldsExactlyOneSchedulerAndItIsTheOneItDeclares() {
        LaunchOutcome outcome = ConfiaApplication.launch(TestProcessArguments.forProcess("worker"),
                "worker");
        ConfigurableApplicationContext context = outcome.context();
        assertThat(context).isNotNull();
        try {
            assertThat(context.getBeansOfType(Scheduler.class)).as("exactly one Scheduler")
                    .hasSize(1);
            String origin = context.getBeanFactory()
                    .getBeanDefinition(context.getBeanNamesForType(Scheduler.class)[0])
                    .getResourceDescription();
            assertThat(origin).as("declared by the worker, not by the library auto-configuration")
                    .contains("WorkerJobsConfiguration");
            assertThat(context.getBeansOfType(DbSchedulerStarter.class)).as("one starter")
                    .hasSize(1).values().allSatisfy(starter -> assertThat(starter)
                            .isExactlyInstanceOf(WorkerSchedulerStarter.class));
        } finally {
            context.close();
        }
    }
}
