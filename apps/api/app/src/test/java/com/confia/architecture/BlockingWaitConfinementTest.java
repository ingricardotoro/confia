package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.AccessTarget.MethodCallTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Rule W4 (web-edge-foundations design.md, decision 20; specs/build-integrity, requirement "La
 * espera bloqueante del retardo se confina al materializador"): no production class of a {@code
 * web}, {@code application} or {@code domain} package calls {@code Thread.sleep}, {@code
 * TimeUnit.sleep}, {@code LockSupport.park*} or {@code Object.wait}, except the delay
 * materializer, which is the one place the required delay of an answer is waited for. A wait
 * anywhere else would hold a thread, and in {@code application} or {@code domain} it would hold it
 * inside a transaction. {@link NoBlockingWaitInIdentityTest} stays as it is and keeps its own
 * fixture.
 *
 * <p>Both halves of the repository's two-half convention (ADR-0018). The production half has no
 * empty-set exception, and {@link #theScopeHoldsRealClassesOfAllThreeKindsOfPackage()} and {@link
 * #theMaterializerIsTheOnlyProductionClassInScopeThatWaits()} prove that it looked at real classes
 * and that the exemption is spent on a class that really waits. The fixture half must reject each
 * of the four primitives, naming the class.
 */
class BlockingWaitConfinementTest {

    private static final String MATERIALIZER =
            "com.confia.shared.web.delay.RequiredDelayMaterializer";
    private static final List<String> SCOPED_PACKAGE_SEGMENTS =
            List.of(".web.", ".application.", ".domain.");

    private static final ArchRule RULE = classes()
            .should(notWaitUnlessTheyAreTheMaterializer())
            .because("only the delay materializer may block a thread waiting, and only for the "
                    + "required delay of an answer (web-edge-foundations design.md, decision 18 "
                    + "and decision 20, rule W4)");

    @Test
    void productionCodeWaitsOnlyInTheMaterializer() {
        RULE.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureThatUsesEachBlockingWaitPrimitive() {
        assertRuleRejects(RULE, fixtureClasses(), "BadSleepingWebComponent", "Thread.sleep",
                "TimeUnit.sleep", "LockSupport.parkNanos", "Object.wait");
    }

    /** Non-vacuity of the scope: the rule evaluates real classes of each of the three kinds. */
    @Test
    void theScopeHoldsRealClassesOfAllThreeKindsOfPackage() {
        List<JavaClass> scoped = productionClasses().stream()
                .filter(BlockingWaitConfinementTest::isInScope).toList();

        for (String segment : SCOPED_PACKAGE_SEGMENTS) {
            assertThat(scoped).as("production classes of a %s package", segment)
                    .anyMatch(javaClass -> ("." + javaClass.getPackageName() + ".")
                            .contains(segment));
        }
        assertThat(scoped.stream().map(JavaClass::getName))
                .contains("com.confia.shared.web.problem.ProblemBody", MATERIALIZER);
    }

    /**
     * Non-vacuity of the exemption: without it, the one class of the scope that calls a waiting
     * primitive is the materializer, so the rule passes because the rest is clean and not because
     * it saw nothing.
     */
    @Test
    void theMaterializerIsTheOnlyProductionClassInScopeThatWaits() {
        List<String> waiting = productionClasses().stream()
                .filter(BlockingWaitConfinementTest::isInScope)
                .filter(javaClass -> !waitCallsOf(javaClass).isEmpty())
                .map(JavaClass::getName)
                .sorted()
                .toList();

        assertThat(waiting).containsExactly(MATERIALIZER);
        assertThat(waitCallsOf(productionClasses().get(MATERIALIZER)))
                .as("the materializer really waits, with Thread.sleep inside its default timer")
                .contains("Thread.sleep");
    }

    private static ArchCondition<JavaClass> notWaitUnlessTheyAreTheMaterializer() {
        return new ArchCondition<>("not call Thread.sleep, TimeUnit.sleep, LockSupport.park* or "
                + "Object.wait from a web, application or domain package, unless they are the "
                + "delay materializer") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (!isInScope(javaClass) || MATERIALIZER.equals(javaClass.getName())) {
                    return;
                }
                for (String call : waitCallsOf(javaClass)) {
                    events.add(SimpleConditionEvent.violated(javaClass,
                            javaClass.getFullName() + " calls " + call + "(...), a thread-blocking "
                                    + "wait that only the delay materializer may make"));
                }
            }
        };
    }

    /** A {@code web}, {@code application} or {@code domain} package, or the fixture that is one. */
    private static boolean isInScope(JavaClass javaClass) {
        String packageName = "." + javaClass.getPackageName() + ".";
        return SCOPED_PACKAGE_SEGMENTS.stream().anyMatch(packageName::contains);
    }

    /** The waiting primitives the class calls, as {@code Owner.method}. */
    private static List<String> waitCallsOf(JavaClass javaClass) {
        return javaClass.getMethodCallsFromSelf().stream()
                .map(JavaMethodCall::getTarget)
                .map(BlockingWaitConfinementTest::blockingWait)
                .flatMap(Optional::stream)
                .toList();
    }

    private static Optional<String> blockingWait(MethodCallTarget target) {
        String owner = target.getOwner().getFullName();
        String method = target.getName();
        if ("java.lang.Thread".equals(owner) && "sleep".equals(method)) {
            return Optional.of("Thread.sleep");
        }
        if ("java.util.concurrent.TimeUnit".equals(owner) && "sleep".equals(method)) {
            return Optional.of("TimeUnit.sleep");
        }
        if ("java.util.concurrent.locks.LockSupport".equals(owner) && method.startsWith("park")) {
            return Optional.of("LockSupport." + method);
        }
        // An unqualified wait(...) names the calling class as its owner in the bytecode, so
        // Object.wait is recognised by its three overloads: (), (long) and (long, int).
        if ("wait".equals(method) && isAnObjectWaitOverload(target)) {
            return Optional.of("Object.wait");
        }
        return Optional.empty();
    }

    private static boolean isAnObjectWaitOverload(MethodCallTarget target) {
        List<String> parameters = target.getRawParameterTypes().stream()
                .map(JavaClass::getName).toList();
        return parameters.isEmpty() || parameters.equals(List.of("long"))
                || parameters.equals(List.of("long", "int"));
    }
}
