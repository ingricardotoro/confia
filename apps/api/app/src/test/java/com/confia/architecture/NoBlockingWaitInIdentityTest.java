package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.AccessTarget.MethodCallTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

/**
 * design.md decision 10, point 4 (ADR-0018); specs/identity/spec.md, requirement "El retardo se
 * calcula dentro de la transacción y se materializa fuera de ella", escenario "Ninguna clase del
 * módulo de identidad espera": no production class of {@code com.confia.identity..} may call
 * {@code Thread.sleep}, {@code TimeUnit.sleep}, {@code Object.wait} or {@code LockSupport.park*}.
 * The required delay is a value the use case returns and the caller materializes at the HTTP edge
 * (design.md, decision 1) — never a wait this module holds a thread, a connection or a row lock
 * open for.
 *
 * <p>The one-package scope ({@code com.confia.identity..}) is a manual check inside the condition
 * itself, not ArchUnit's own {@code .that().resideInAPackage(...)}: that keeps the same rule object
 * usable against both {@link ArchitectureTestSupport#productionClasses()} (the whole
 * {@code com.confia} tree) and {@link ArchitectureTestSupport#fixtureClasses()}, whose permanent
 * violation fixture physically lives under {@code com.confia.architecture.fixture.identity} — a
 * package this condition treats as "simulating" {@code com.confia.identity} for that one purpose,
 * exactly as {@link BadBlockingWaitInIdentityTest} names it (design.md decision 10, point 4).
 */
class NoBlockingWaitInIdentityTest {

    private static final String IDENTITY_PACKAGE_PREFIX = "com.confia.identity";
    private static final String SIMULATED_IDENTITY_FIXTURE_PACKAGE =
            "com.confia.architecture.fixture.identity";

    private static final String THREAD_TYPE = "java.lang.Thread";
    private static final String TIME_UNIT_TYPE = "java.util.concurrent.TimeUnit";
    private static final String OBJECT_TYPE = "java.lang.Object";
    private static final String LOCK_SUPPORT_TYPE = "java.util.concurrent.locks.LockSupport";

    private static final ArchRule RULE = classes()
            .should(notCallAnyBlockingWaitPrimitive())
            .because("no production class of com.confia.identity.. may block the current thread "
                    + "waiting (design.md decision 10, point 4; specs/identity/spec.md, \"Ninguna "
                    + "clase del módulo de identidad espera\")");

    @Test
    void productionCodeInIdentityNeverCallsABlockingWaitPrimitive() {
        RULE.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureBlockingWaitUsage() {
        assertRuleRejects(RULE, fixtureClasses(), "BadBlockingWaitInIdentity", "Thread.sleep");
    }

    private static ArchCondition<JavaClass> notCallAnyBlockingWaitPrimitive() {
        return new ArchCondition<>("not call Thread.sleep, TimeUnit.sleep, Object.wait or "
                + "LockSupport.park* from com.confia.identity..") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (!isIdentityScoped(javaClass)) {
                    return;
                }
                for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                    MethodCallTarget target = call.getTarget();
                    if (isBlockingWaitPrimitive(target)) {
                        events.add(SimpleConditionEvent.violated(javaClass,
                                javaClass.getFullName() + " calls "
                                        + target.getOwner().getFullName() + "."
                                        + target.getName() + "(...), a thread-blocking wait "
                                        + "primitive forbidden in com.confia.identity.."));
                    }
                }
            }
        };
    }

    private static boolean isIdentityScoped(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();
        return packageName.equals(IDENTITY_PACKAGE_PREFIX)
                || packageName.startsWith(IDENTITY_PACKAGE_PREFIX + ".")
                || packageName.equals(SIMULATED_IDENTITY_FIXTURE_PACKAGE);
    }

    private static boolean isBlockingWaitPrimitive(MethodCallTarget target) {
        String ownerName = target.getOwner().getFullName();
        String methodName = target.getName();
        if (THREAD_TYPE.equals(ownerName) && "sleep".equals(methodName)) {
            return true;
        }
        if (TIME_UNIT_TYPE.equals(ownerName) && "sleep".equals(methodName)) {
            return true;
        }
        if (OBJECT_TYPE.equals(ownerName) && "wait".equals(methodName)) {
            return true;
        }
        return LOCK_SUPPORT_TYPE.equals(ownerName) && methodName.startsWith("park");
    }
}
