package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Rule W3 (web-edge-foundations design.md, decision 20; specs/build-integrity, requirement "Reglas
 * de dependencia de la capa {@code web} de producción"): nothing under {@code <root>.shared} may
 * depend on a class of any other package under {@code <root>}, except {@code <root>.shared} itself
 * and {@code <root>.kernel}. In particular {@code shared.security} and {@code shared.web} never
 * depend on {@code identity}: {@code shared} is what a business module depends on, so the
 * arrow back would be a cycle that Spring Modulith would report only as a cycle (decision 20).
 *
 * <p>Built from a root package, like {@link BootstrapEntryPointRulesTest}, and checked twice: the
 * production half with {@value #PRODUCTION_ROOT}, which evaluates the real classes of {@code
 * shared} and carries no empty-set exception, and the fixture half with {@value #FIXTURE_ROOT},
 * which must be rejected naming the class that reaches {@code identity} and the class it reaches
 * (ADR-0018). The fixture lives under its own root so that no other rule sees a {@code shared}
 * package that depends on a module.
 */
class SharedBoundaryRulesTest {

    private static final String PRODUCTION_ROOT = "com.confia";
    private static final String FIXTURE_ROOT = "com.confia.architecture.fixture.sharedboundary";

    static ArchRule sharedDependsOnlyOnSharedAndKernel(String root) {
        return noClasses()
                .that().resideInAPackage(root + ".shared..")
                .should().dependOnClassesThat(resideInAPackage(root + "..")
                        .and(not(resideInAnyPackage(root + ".shared..", root + ".kernel.."))))
                .because("shared is what every module depends on, so it never depends on a "
                        + "module back; it may use only itself and the kernel (web-edge-"
                        + "foundations design.md, decision 20)");
    }

    @Test
    void productionSharedDependsOnlyOnSharedAndKernel() {
        sharedDependsOnlyOnSharedAndKernel(PRODUCTION_ROOT).check(productionClasses());
    }

    @Test
    void rejectsTheFixtureSharedClassThatDependsOnIdentity() {
        assertRuleRejects(sharedDependsOnlyOnSharedAndKernel(FIXTURE_ROOT), fixtureClasses(),
                "BadSharedUsesIdentity", "SomeIdentityType");
    }
}
