package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ADR-0015 compliance 2, design.md decision 9 rule R1: jOOQ and the generated jOOQ package are
 * confined to {@code infrastructure}. No other layer may import {@code org.jooq} or {@code
 * confia.generated} types directly. No {@code allowEmptyShould(true)}: this rule's scope is every
 * class outside {@code infrastructure}, which is never empty.
 */
class JooqConfinedToInfrastructureTest {

    private static final ArchRule RULE = noClasses()
            .that().resideOutsideOfPackage("..infrastructure..")
            .should().dependOnClassesThat().resideInAnyPackage("org.jooq..", "confia.generated..")
            .because("jOOQ and the generated jOOQ package live only in infrastructure "
                    + "(ADR-0015, regla 4)");

    @Test
    void productionCodeConfinesJooqToInfrastructure() {
        RULE.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureJooqUsageOutsideInfrastructure() {
        assertRuleRejects(RULE, fixtureClasses(), "BadJooqUser");
    }
}
