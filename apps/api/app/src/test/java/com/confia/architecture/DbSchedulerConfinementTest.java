package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Scenario BI100 (background-jobs-with-db-scheduler design.md decisions 3 and 21): the types of
 * {@code com.github.kagkarlsson} appear only in {@code com.confia.shared.jobs.infrastructure}. A business
 * module declares its jobs with the JDK types of {@code shared.jobs} and never depends on the library.
 */
class DbSchedulerConfinementTest {

    private static final ArchRule RULE = noClasses()
            .that().resideOutsideOfPackage("com.confia.shared.jobs.infrastructure..")
            .should().dependOnClassesThat().resideInAPackage("com.github.kagkarlsson..")
            .because("the scheduling library stays inside shared.jobs.infrastructure (design.md decision 3)");

    @Test
    void onlyTheJobsInfrastructureDependsOnTheLibrary() {
        assertThat(productionClasses().stream().filter(c -> c.getPackageName()
                .startsWith("com.confia.shared.jobs.infrastructure")).count())
                .as("non-vacuous: the confined package exists and is populated").isGreaterThan(5);

        RULE.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureOfAnotherModule() {
        assertRuleRejects(RULE, fixtureClasses(), "ImportsTheSchedulingLibrary");
    }
}
