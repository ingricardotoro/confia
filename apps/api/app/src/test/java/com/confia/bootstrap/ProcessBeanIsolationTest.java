package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Specs/build-integrity of process-entry-point-isolation: each of the three processes, started
 * through the production launcher and without a database, contains only the {@code com.confia}
 * beans its {@link ProcessBeanPolicy} allows (ADR-0003, ADR-0024). The allow-list fails closed;
 * the forbidden list is nominal and additional.
 */
class ProcessBeanIsolationTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.confia.bootstrap.ProcessBeanPolicy#all")
    void registersOnlyItsAllowedBeans(ProcessBeanPolicy policy) {
        String[] args = "worker".equals(policy.process())
                ? new String[0]
                : new String[] {"--server.port=0"};
        LaunchOutcome outcome = ConfiaApplication.launch(args, policy.process());
        ConfigurableApplicationContext context = outcome.context();
        try {
            assertThat(context).as("the %s process must start", policy.process()).isNotNull();
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(ProcessBeanInspector.violations(context, policy))
                    .as("beans outside the allow-list or in a forbidden package")
                    .isEmpty();
            softly.assertThat(ProcessBeanInspector.entryPointConfigurations(context))
                    .as("exactly one entry point configuration in the %s context",
                            policy.process())
                    .hasSize(1);
            softly.assertThat(ProcessBeanInspector.confiaBeans(context))
                    .as("non-vacuous: the inspector must see real beans of the entry package")
                    .anyMatch(bean -> bean.originPackages().contains(policy.entryPackage()));
            softly.assertAll();
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    /**
     * The db-scheduler prohibition for the admin and portal processes passes vacuously while the
     * library is not on the classpath (ADR-0018). This test says so out loud and fails the day
     * change 9 (background-jobs-with-db-scheduler) adds it: that change replaces it with the
     * positive worker assertion. The prohibition itself needs no rewrite, because the inspector
     * already evaluates it on every bean.
     */
    @Test
    void dbSchedulerAbsenceIsVacuousUntilChange9() {
        assertThat(isOnClasspath("com.github.kagkarlsson.scheduler.Scheduler"))
                .as("db-scheduler is now on the classpath: the forbidden com.github.kagkarlsson "
                        + "package is no longer vacuous in admin and portal (ADR-0018). Replace "
                        + "this test with the positive worker assertion (change 9)")
                .isFalse();
    }

    private static boolean isOnClasspath(String className) {
        try {
            Class.forName(className, false, ProcessBeanIsolationTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
