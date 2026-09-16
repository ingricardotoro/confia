package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ADR-0002 "domain-is-pure" / "application-no-infrastructure" / "interface-only-application",
 * collapsed into one direction check: nothing in a {@code domain} package may import
 * {@code application}, {@code infrastructure} or {@code web} of any module (its own module
 * included). {@code domain} depends on nothing but the JDK and {@code kernel}.
 */
class DomainDoesNotDependOnOuterLayersTest {

    private static final ArchRule RULE = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..application..", "..infrastructure..", "..web..")
            .because("domain depends on nothing but the JDK and kernel (ADR-0002); "
                    + "application, infrastructure and web depend on domain, never the reverse");

    @Test
    void productionDomainHasNoOuterLayerDependencyYet() {
        RULE.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureDomainImportingInfrastructure() {
        assertRuleRejects(RULE, fixtureClasses());
    }
}
