package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

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
 * ADR-0015 compliance 3, design.md decision 9 rule R3: only the single transactional component of
 * {@code com.confia.shared.security} may open a database transaction ({@code @Transactional},
 * {@code TransactionTemplate} or {@code PlatformTransactionManager}). That component does not
 * exist yet in this part of the change (F0 change 5, part A); this rule is a preventive guard so
 * the first jOOQ adapter never opens its own transaction. It is evaluated over {@link
 * ArchitectureTestSupport#productionClasses()}, which excludes {@code target/test-classes}, so the
 * free use of {@code @Transactional} in {@code *IT.java} integration tests (design.md decision 7)
 * is unaffected.
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

    private static ArchCondition<JavaClass> notUseAnyTransactionApiOutsideSharedSecurity() {
        return new ArchCondition<>("not use @Transactional, TransactionTemplate or "
                + "PlatformTransactionManager outside shared.security") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (javaClass.getPackageName().startsWith("com.confia.shared.security")) {
                    return;
                }
                if (javaClass.isAnnotatedWith(Transactional.class)) {
                    events.add(SimpleConditionEvent.violated(javaClass,
                            javaClass.getFullName() + " is annotated with @Transactional"));
                }
                for (JavaMethod method : javaClass.getMethods()) {
                    if (method.isAnnotatedWith(Transactional.class)) {
                        events.add(SimpleConditionEvent.violated(javaClass,
                                javaClass.getFullName() + "." + method.getName()
                                        + "(..) is annotated with @Transactional"));
                    }
                }
                for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                    String targetName = dependency.getTargetClass().getFullName();
                    if (TRANSACTION_API_TYPES.contains(targetName)) {
                        events.add(SimpleConditionEvent.violated(javaClass,
                                javaClass.getFullName() + " depends on " + targetName));
                    }
                }
            }
        };
    }
}
