package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ADR-0002 "no-cycles": no two packages may depend on each other. Sliced one level below the
 * scanned root, so the same rule shape checks module-level cycles in production
 * ({@code com.confia.<module>}) and the permanent two-package cycle fixture
 * ({@code com.confia.architecture.fixture.cycle.<packagea|packageb>}, task 3.3).
 */
class NoCyclesTest {

    @Test
    void productionPackagesHaveNoCycleYet() {
        ArchRule productionRule = slices().matching("com.confia.(*)..").should().beFreeOfCycles();
        productionRule.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureTwoPackageCycle() {
        ArchRule fixtureRule = slices()
                .matching("com.confia.architecture.fixture.cycle.(*)..")
                .should().beFreeOfCycles();
        assertRuleRejects(fixtureRule, fixtureClasses());
    }
}
