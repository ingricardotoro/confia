package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
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
    void everyModuleUsesOnlyItsOwnDomain() {
        RULE.check(productionClasses());
    }

    /**
     * Non-vacuity guard (design.md, decision 13): the scenario "Cada módulo usa solo su propio
     * dominio" (specs/build-integrity/spec.md) stops being a vacuous truth only once at least two
     * distinct business modules' {@code domain} packages exist in production code at the same
     * time. Before this change, {@code organization.domain} was the only one, so
     * {@link #everyModuleUsesOnlyItsOwnDomain()} passed for lack of a second domain to cross into,
     * not because the rule was demonstrated. If a future change ever deleted every class of a
     * second module's domain, this test would fail instead of silently passing again.
     */
    @Test
    void atLeastTwoDistinctModuleDomainsExistInProductionCode() {
        JavaClasses classes = productionClasses();
        Set<String> domainModules = classes.stream()
                .map(NoCrossModuleDomainImportsTest::domainModuleOf)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toUnmodifiableSet());

        assertThat(domainModules)
                .as("production code must contain at least two distinct modules' domain packages "
                        + "for this rule's positive half to be anything other than vacuously true")
                .hasSizeGreaterThanOrEqualTo(2);
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
