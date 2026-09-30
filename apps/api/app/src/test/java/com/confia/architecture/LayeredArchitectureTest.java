package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.Architectures.LayeredArchitecture;
import org.junit.jupiter.api.Test;

/**
 * ADR-0002 "domain-is-pure", "application-no-infrastructure" and "interface-only-application",
 * expressed as a single {@link com.tngtech.archunit.library.Architectures#layeredArchitecture()}
 * rule (verify-report.md, C1, "una regla layeredArchitecture() completa"): {@code web} depends
 * only on {@code application}, {@code application} depends only on {@code domain}, {@code
 * infrastructure} implements {@code application}'s ports by depending on it and on {@code domain},
 * and nothing is ever depended on by a layer it is not allowed to reach: {@code domain} and {@code
 * infrastructure} are never imported by anything among these four layers other than what this rule
 * allows, and {@code web} is never imported by any of them.
 *
 * <p>This supersedes the narrower rule this file used to hold, which checked only that {@code
 * domain} does not depend on outer layers and whose Javadoc incorrectly claimed the other two
 * ADR-0002 layer rules were "collapsed" into that single check. They were never implemented; this
 * rule implements all three.
 *
 * <p><b>verify-report.md C1-bis.</b> The rule used to call {@code .consideringAllDependencies()},
 * which evaluates every dependency reachable from a layer's classes, including ones to types that
 * belong to no declared layer at all — {@code java.lang.Object}, {@code java.lang.String}, and any
 * other JDK type every class implicitly depends on. Against real code that produced dozens of
 * spurious violations per class (probe H: 37 reported violations against the fixture, only 4
 * deliberate) and, worse, made the fixture-rejecting test pass on a fixture with every deliberate
 * violation removed (probe G), because JDK noise alone kept throwing an {@code AssertionError}.
 * {@code .consideringOnlyDependenciesInLayers()} replaces it: confirmed by decompiling {@code
 * Architectures$LayeredArchitecture$DependencySettings} in the resolved archunit 1.4.2 jar with
 * {@code javap -c}, this setting marks a dependency irrelevant whenever its origin or its target is
 * not matched by any declared layer's package pattern — exactly {@code java.lang.Object} and {@code
 * java.lang.String} — while {@code .consideringAllDependencies()} applies no such filter at all
 * (its lambda is the identity function). No package prefix needs to be hardcoded: the filter is
 * derived from the four layer definitions below, so it stays correct if the base package ever
 * changes.
 */
class LayeredArchitectureTest {

    /**
     * The rule evaluated against real production code (ADR-0020 §2, option A). {@code
     * Infrastructure} became mandatory in PR A2, F0 change 5, the moment {@link
     * com.confia.organization.infrastructure.JooqInstitutionRepository} became this codebase's
     * first production class in an {@code infrastructure} package (ADR-0020, alcance punto 3;
     * design.md decision 11): the entry that used to declare that exception, and the {@code
     * optionalLayer("Infrastructure")} call itself, are both retired in this same commit. {@code
     * Domain} and {@code Application} stay always mandatory, per ADR-0020 §2, point 2: a class that
     * accidentally stops landing in one of those two must still fail the build.
     *
     * <p>{@code Web} became mandatory in F0 change 3 (frontend-monorepo-and-contracts-pipeline,
     * task 1.3), the moment {@link com.confia.shared.web.openapi.ContractSchemas} became this
     * codebase's first production class in a {@code web} package. Its ADR-0020 exception, the
     * {@code optionalLayer} call and its inventory entry were retired in the same commit, by the
     * owner's decision of 2026-09-30, following ADR-0018's expiry procedure. No layer is optional
     * any more.
     */
    private static ArchRule productionLayeringRule() {
        return constrained(layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("Domain").definedBy("..domain..")
                .layer("Application").definedBy("..application..")
                .layer("Infrastructure").definedBy("..infrastructure..")
                .layer("Web").definedBy("..web.."));
    }

    /**
     * The rule evaluated against the permanent fixture package (ADR-0020 §2, point 1: "nunca en
     * la mitad de fixture"). Every layer stays mandatory: {@link
     * ArchitectureTestSupport#fixtureClasses()} always has classes in all four layers (task 3.3 of
     * the maven-workspace-and-ci-skeleton change), and a fixture that stopped matching one of them
     * must fail the build, not read as a legitimate rejection (ADR-0018 §1.4).
     */
    private static ArchRule fixtureLayeringRule() {
        return constrained(layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("Domain").definedBy("..domain..")
                .layer("Application").definedBy("..application..")
                .layer("Infrastructure").definedBy("..infrastructure..")
                .layer("Web").definedBy("..web.."));
    }

    /** The eight {@code whereLayer} dependency clauses shared by both rule halves (ADR-0002). */
    private static ArchRule constrained(LayeredArchitecture layers) {
        return layers
                .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Infrastructure")
                .whereLayer("Domain").mayNotAccessAnyLayer()
                .whereLayer("Application").mayOnlyBeAccessedByLayers("Web", "Infrastructure")
                .whereLayer("Application").mayOnlyAccessLayers("Domain")
                .whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()
                .whereLayer("Infrastructure").mayOnlyAccessLayers("Application", "Domain")
                .whereLayer("Web").mayNotBeAccessedByAnyLayer()
                .whereLayer("Web").mayOnlyAccessLayers("Application")
                .because("web depends only on application, application depends only on domain, "
                        + "infrastructure implements application's ports by depending on it and "
                        + "on domain, and nothing depends back the other way (ADR-0002)");
    }

    /**
     * {@code true} when no class in {@code classes} lives in a package whose name contains {@code
     * layerSegment} as a dot-delimited segment (the same matching {@code "..<segment>.."} package
     * predicates use). Package-visible for {@link EmptyShouldExceptionInventoryTest}'s ADR-0020
     * expiry conditions.
     */
    static boolean noProductionClassInLayer(JavaClasses classes, String layerSegment) {
        String needle = "." + layerSegment + ".";
        return classes.stream()
                .noneMatch(javaClass -> ("." + javaClass.getPackageName() + ".").contains(needle));
    }

    @Test
    void productionCodeRespectsLayering() {
        productionLayeringRule().check(productionClasses());
    }

    @Test
    void rejectsTheFixtureLayeringViolations() {
        // Never allowEmptyShould here (ADR-0018): the fixture package is never empty by
        // construction, and a rule that stopped inspecting it must fail the build, not read as a
        // legitimate rejection.
        //
        // verify-report.md C1-bis: asserting only isInstanceOf(AssertionError.class) let this test
        // pass on a fixture neutralized of every real violation (probe G), because
        // consideringAllDependencies() kept throwing on JDK noise alone. Naming the three fixture
        // classes that must appear in the violation message closes that gap: a build where the rule
        // still throws but no longer names BadDomain, BadApplication or BadWeb is not a rejection of
        // the layering violations, it is noise, and this assertion now tells the two apart.
        assertRuleRejects(fixtureLayeringRule(), fixtureClasses(), "BadDomain", "BadApplication",
                "BadWeb");
    }
}
