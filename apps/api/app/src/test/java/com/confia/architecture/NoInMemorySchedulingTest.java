package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.lang.ArchRule;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Scenarios BI85 and BI86 (ADR-0016 compliance 2; CLAUDE.md): no production class schedules in
 * memory. The only scheduler of the system is db-scheduler on PostgreSQL, so nothing may depend on
 * {@code @Scheduled}, {@code @Schedules}, {@code @EnableScheduling}, {@code @Async}, {@code
 * @EnableAsync}, any type of {@code org.springframework.scheduling}, {@code java.util.Timer} or {@code
 * ScheduledExecutorService}. An annotation counts as a dependency of the class or method it marks.
 * The fixtures hold one class for each annotation and each type, and the rule is evaluated over
 * real production classes, never over an empty set.
 */
class NoInMemorySchedulingTest {

    private static final Set<String> FORBIDDEN_TYPES = Set.of("java.util.Timer", "java.util.TimerTask",
            "java.util.concurrent.ScheduledExecutorService", "java.util.concurrent.ScheduledThreadPoolExecutor");

    private static final ArchRule RULE = noClasses()
            .should().dependOnClassesThat(describe("schedule in memory", target ->
                    target.getPackageName().startsWith("org.springframework.scheduling")
                            || FORBIDDEN_TYPES.contains(target.getFullName())))
            .because("the only scheduler is db-scheduler on PostgreSQL (ADR-0016)");

    @Test
    void productionCodeSchedulesNothingInMemoryAndTheRuleSeesRealClasses() {
        assertThat(productionClasses().stream().count()).as("non-vacuous: real production classes").isGreaterThan(100);

        RULE.check(productionClasses());
    }

    @Test
    void rejectsEachFixtureNamingItsClass() {
        assertRuleRejects(RULE, fixtureClasses(), "UsesScheduledAnnotation", "UsesSchedulesAnnotation",
                "UsesEnableScheduling", "UsesAsyncAnnotation", "UsesEnableAsync", "UsesSpringSchedulingType",
                "UsesTimer", "UsesScheduledExecutorService");
    }
}
