package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Structural rule (confia-module-scaffold skill, §1): no business-module package may be named
 * {@code interface}, {@code interfaces}, {@code controllers}, {@code services},
 * {@code repositories}, {@code entities}, {@code utils}, {@code helpers} or {@code common}. A
 * package name must describe a business capability, never a technical layer.
 */
class NoTechnicalLayerPackageNamesTest {

    private static final ArchRule RULE = noClasses()
            .should().resideInAnyPackage(
                    "..interface..", "..interfaces..", "..controllers..", "..services..",
                    "..repositories..", "..entities..", "..utils..", "..helpers..", "..common..")
            .because("a package name must describe a business capability, never a technical "
                    + "layer (confia-module-scaffold skill, section 1)");

    @Test
    void productionCodeHasNoTechnicalLayerPackageNameYet() {
        RULE.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureServicesPackage() {
        assertRuleRejects(RULE, fixtureClasses());
    }
}
