package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import java.util.Arrays;
import java.util.Optional;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

/**
 * ADR-0002 "no-cross-module-domain": no module's {@code domain} package may import another
 * module's {@code domain} package. Communication between modules happens through a public use
 * case or a domain event, never a direct domain-to-domain import.
 *
 * <p>A class's "domain module" is everything in its package name before the first {@code domain}
 * segment. That makes this rule apply unchanged to a real business module
 * ({@code com.confia.payments.domain}, module {@code com.confia.payments}) and to the permanent
 * fixture ({@code com.confia.architecture.fixture.moduleone.domain}, module
 * {@code com.confia.architecture.fixture.moduleone}) without hardcoding any module name.
 */
class NoCrossModuleDomainImportsTest {

    private static final ArchRule RULE = classes()
            .should(notDependOnAnotherModulesDomain())
            .because("a module's domain must never import another module's domain directly "
                    + "(ADR-0002); use a public use case or a domain event instead");

    @Test
    void productionCodeHasNoCrossModuleDomainImportYet() {
        RULE.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureCrossModuleDomainImport() {
        assertRuleRejects(RULE, fixtureClasses());
    }

    private static ArchCondition<JavaClass> notDependOnAnotherModulesDomain() {
        return new ArchCondition<>("not depend on another module's domain package") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                Optional<String> ownModule = domainModuleOf(javaClass);
                if (ownModule.isEmpty()) {
                    return;
                }
                for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    Optional<String> targetModule = domainModuleOf(target);
                    if (targetModule.isPresent() && !targetModule.equals(ownModule)) {
                        String message = String.format(
                                "%s imports %s, crossing from module '%s' domain into module "
                                        + "'%s' domain",
                                javaClass.getFullName(), target.getFullName(), ownModule.get(),
                                targetModule.get());
                        events.add(SimpleConditionEvent.violated(javaClass, message));
                    }
                }
            }
        };
    }

    /**
     * The package prefix strictly before the first {@code domain} segment, or empty if the class
     * does not reside in a {@code domain} package at all.
     */
    private static Optional<String> domainModuleOf(JavaClass javaClass) {
        String[] segments = javaClass.getPackageName().split("\\.");
        int domainIndex = Arrays.asList(segments).indexOf("domain");
        if (domainIndex < 0) {
            return Optional.empty();
        }
        return Optional.of(String.join(".", Arrays.copyOfRange(segments, 0, domainIndex)));
    }
}
