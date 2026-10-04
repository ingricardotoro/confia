package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ComponentScan;

/**
 * Structural rules of the process entry points (ADR-0003, ADR-0024). Each rule is built once from
 * a root package and checked twice, as everywhere else in this package: against production
 * ({@value #PRODUCTION_ROOT}), which must be clean, and against the permanent fixture
 * ({@value #FIXTURE_ROOT}), which must be rejected naming the deliberate offender (ADR-0018).
 *
 * <p>Together with the bean-level checks in {@code ProcessBeanIsolationTest} they keep the shape
 * that makes the isolation hold: one sub-package per process, no entry point scanning components
 * and nothing outside {@code bootstrap} reaching into the per-process wiring.
 */
class BootstrapEntryPointRulesTest {

    private static final String PRODUCTION_ROOT = "com.confia.bootstrap";
    private static final String FIXTURE_ROOT = "com.confia.architecture.fixture.entrypoints";

    static ArchRule entryPointsDoNotDependOnEachOther(String root) {
        return slices().matching(root + ".(*)..").should().notDependOnEachOther()
                .because("each process declares its own modules; one entry point never reaches "
                        + "another's wiring (ADR-0003, ADR-0024)");
    }

    static ArchRule nothingOutsideReferencesAnEntryPoint(String root) {
        return noClasses().that().resideOutsideOfPackage(root + "..")
                .should().dependOnClassesThat().resideInAPackage(root + ".*..")
                .because("entry point classes are public only for the launcher (ADR-0024)");
    }

    static ArchRule noEntryPointScansComponents(String root) {
        return noClasses().that().resideInAPackage(root + "..")
                .should().beMetaAnnotatedWith(ComponentScan.class)
                .because("an entry point registers what it loads with @Import, never by scanning "
                        + "(ADR-0024)");
    }

    @Test
    void productionEntryPointsDoNotDependOnEachOther() {
        entryPointsDoNotDependOnEachOther(PRODUCTION_ROOT).check(productionClasses());
    }

    @Test
    void rejectsTheFixtureEntryPointDependingOnAnotherEntryPoint() {
        assertRuleRejects(entryPointsDoNotDependOnEachOther(FIXTURE_ROOT), fixtureClasses(),
                "CrossEntryPointDependency", "ScanningEntryPoint");
    }

    @Test
    void productionHasNothingOutsideBootstrapReferencingAnEntryPoint() {
        nothingOutsideReferencesAnEntryPoint(PRODUCTION_ROOT).check(productionClasses());
    }

    @Test
    void rejectsTheFixtureClassOutsideTheRootReferencingAnEntryPoint() {
        assertRuleRejects(nothingOutsideReferencesAnEntryPoint(FIXTURE_ROOT), fixtureClasses(),
                "OutsideEntryPointReference", "ScanningEntryPoint");
    }

    @Test
    void productionEntryPointsDoNotScanComponents() {
        noEntryPointScansComponents(PRODUCTION_ROOT).check(productionClasses());
    }

    @Test
    void rejectsTheFixtureEntryPointThatScansComponents() {
        assertRuleRejects(noEntryPointScansComponents(FIXTURE_ROOT), fixtureClasses(),
                "ScanningEntryPoint", "ComponentScan");
    }
}
