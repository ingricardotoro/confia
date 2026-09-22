package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-0015 compliance 3, design.md decision 13 rule R3: only the single transactional component of
 * {@code com.confia.shared.security} may open a database transaction ({@code @Transactional},
 * {@code TransactionTemplate} or {@code PlatformTransactionManager}). It is evaluated over {@link
 * ArchitectureTestSupport#productionClasses()}, which excludes {@code target/test-classes}, so the
 * free use of {@code @Transactional} in {@code *IT.java} integration tests (design.md decision 7 of
 * the previous change) is unaffected.
 *
 * <p><b>Two independent assertions, sharing one criterion.</b> {@link
 * #usesTransactionApi(JavaClass)} is the single definition of "uses the transaction API" that both
 * halves use: the negative rule below (everything outside {@code shared.security} must not use it)
 * and {@link #sharedSecurityContainsAtLeastOneProductionClassThatUsesTheTransactionApi()} (something
 * inside {@code shared.security} must use it). Before {@link com.confia.shared.security.TransactionRunner}
 * existed (F0 change 5, part A), this class only had the negative half, which stayed green as a pure
 * guard even with an empty {@code shared.security} package — exactly the gap the positive assertion
 * closes: a rule that only ever rejects a fixture protects nothing about whether the real component
 * actually exists (specs/build-integrity/spec.md, "El componente transaccional único satisface la
 * aserción positiva").
 */
class TransactionsOnlyInSharedSecurityTest {

    private static final Set<String> TRANSACTION_API_TYPES =
            Set.of("org.springframework.transaction.support.TransactionTemplate",
                    "org.springframework.transaction.PlatformTransactionManager");

    private static final ArchRule RULE = classes()
            .should(notUseAnyTransactionApiOutsideSharedSecurity())
            .because("only the single transactional component of shared.security opens "
                    + "transactions (ADR-0015, regla 7)");

    @Test
    void productionCodeOpensNoTransactionOutsideSharedSecurity() {
        RULE.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureTransactionalUsage() {
        assertRuleRejects(RULE, fixtureClasses(), "BadTransactionalRepository");
    }

    @Test
    void sharedSecurityContainsAtLeastOneProductionClassThatUsesTheTransactionApi() {
        boolean componentExists = productionClasses().stream()
                .filter(javaClass -> javaClass.getPackageName().startsWith("com.confia.shared.security"))
                .anyMatch(TransactionsOnlyInSharedSecurityTest::usesTransactionApi);

        assertThat(componentExists)
                .as("com.confia.shared.security must contain at least one production class that "
                        + "actually opens transactions through the transaction API — this is the "
                        + "assertion that fails if the single transactional component were ever "
                        + "removed or reimplemented without the API, something the negative rule "
                        + "above cannot catch on its own")
                .isTrue();
    }

    private static ArchCondition<JavaClass> notUseAnyTransactionApiOutsideSharedSecurity() {
        return new ArchCondition<>("not use @Transactional, TransactionTemplate or "
                + "PlatformTransactionManager outside shared.security") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (javaClass.getPackageName().startsWith("com.confia.shared.security")) {
                    return;
                }
                if (usesTransactionApi(javaClass)) {
                    events.add(SimpleConditionEvent.violated(javaClass,
                            javaClass.getFullName() + " uses the transaction API (@Transactional, "
                                    + "TransactionTemplate or PlatformTransactionManager) outside "
                                    + "com.confia.shared.security"));
                }
            }
        };
    }

    /**
     * The one criterion both halves of this test share (class- or method-level
     * {@code @Transactional}, or a direct dependency on {@link
     * org.springframework.transaction.support.TransactionTemplate} or {@link
     * org.springframework.transaction.PlatformTransactionManager}), so the negative and the
     * positive assertion can never quietly diverge on what "uses the transaction API" means.
     */
    private static boolean usesTransactionApi(JavaClass javaClass) {
        if (javaClass.isAnnotatedWith(Transactional.class)) {
            return true;
        }
        for (JavaMethod method : javaClass.getMethods()) {
            if (method.isAnnotatedWith(Transactional.class)) {
                return true;
            }
        }
        for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
            if (TRANSACTION_API_TYPES.contains(dependency.getTargetClass().getFullName())) {
                return true;
            }
        }
        return false;
    }
}
