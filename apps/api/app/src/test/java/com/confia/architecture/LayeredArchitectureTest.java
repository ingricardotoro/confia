package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.lang.ArchRule;
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

    private static ArchRule layeringRule() {
        return layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("Domain").definedBy("..domain..")
                .layer("Application").definedBy("..application..")
                .layer("Infrastructure").definedBy("..infrastructure..")
                .layer("Web").definedBy("..web..")
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

    @Test
    void productionCodeRespectsLayering() {
        layeringRule().check(productionClasses());
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
        assertRuleRejects(layeringRule(), fixtureClasses(), "BadDomain", "BadApplication", "BadWeb");
    }
}
