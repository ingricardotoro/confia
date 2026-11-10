package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Scenario BI137 (rbac-permission-matrix-and-audit-integration design.md D19): the tests of the cap,
 * the window and the summary of the permission denials move a manual clock and never read the real
 * time or wait. No class of the named tests, nested classes included, calls {@code Thread.sleep},
 * {@code Instant.now}, {@code Clock.systemUTC} or the time sources of the JDK a clock could be read
 * from. The rule is shown on a fixture that does all of it, so it is known to find what it looks for.
 */
class NoRealTimeInDenialTestsTest {

    private static final List<String> NAMED = List.of(
            "com.confia.shared.web.authorization.DenialWindowsTest",
            "com.confia.shared.web.authorization.PermissionDenialAuditIT",
            "com.confia.shared.web.authorization.AuthorizationEdgeIT",
            "com.confia.shared.web.harness.MutableClock",
            "com.confia.shared.web.harness.RecordingAuthorizationMetrics");

    private static final Set<String> REAL_TIME = Set.of("java.lang.Thread.sleep",
            "java.time.Instant.now", "java.time.Clock.systemUTC", "java.time.Clock.systemDefaultZone",
            "java.time.Clock.system", "java.time.LocalDateTime.now", "java.time.OffsetDateTime.now",
            "java.time.ZonedDateTime.now", "java.time.LocalDate.now", "java.lang.System.currentTimeMillis",
            "java.lang.System.nanoTime", "java.util.concurrent.TimeUnit.sleep");

    private static final ArchRule RULE = noClasses().should().callMethodWhere(describe(
            "read the real time or wait", call -> REAL_TIME.contains(
                    call.getTarget().getOwner().getFullName() + "." + call.getTarget().getName())))
            .because("the tests of the cap and the window run on a manual clock (design.md D19)");

    private static JavaClasses named() {
        return new ClassFileImporter().importPackages("com.confia.shared.web")
                .that(describe("are one of the named tests or nested in one", (JavaClass javaClass) ->
                        NAMED.stream().anyMatch(name -> javaClass.getName().equals(name)
                                || javaClass.getName().startsWith(name + "$"))));
    }

    @Test
    void namedTestsReadNoRealTimeAndTheRuleSeesThem() {
        JavaClasses named = named();

        assertThat(named.stream().map(JavaClass::getName)).as("non-vacuous: every named class is found")
                .containsAll(NAMED);
        RULE.check(named);
    }

    @Test
    void rejectsTheFixtureThatSleepsAndReadsTheRealTime() {
        JavaClasses fixture = new ClassFileImporter().importClasses(SleepingFixture.class);

        assertRuleRejects(RULE, fixture, "SleepingFixture", "sleep", "now", "systemUTC");
    }

    /** Deliberately wrong: a test of the cap that waits and reads the real time. */
    static final class SleepingFixture {

        void waits() throws InterruptedException {
            Thread.sleep(1);
            Instant.now();
            Clock.systemUTC();
        }
    }
}
