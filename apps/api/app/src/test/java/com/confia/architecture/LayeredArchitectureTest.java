package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Arrays;
import java.util.Set;
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
 */
class LayeredArchitectureTest {

    private static ArchRule layeringRule() {
        return layeredArchitecture()
                .consideringAllDependencies()
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
    void productionCodeRespectsLayeringYet() {
        // ADR-0018: no business module exists yet under apps/api/app, so all four layers above
        // are empty for production code today. This exception expires the moment a business
        // module (change 4) adds a class to any of them; see EmptyShouldExceptionInventoryTest,
        // whose inventory entry for this rule must be removed together with this call.
        ArchRule rule = layeringRule().allowEmptyShould(true);
        rule.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureLayeringViolations() {
        // Never allowEmptyShould here (ADR-0018): the fixture package is never empty by
        // construction, and a rule that stopped inspecting it must fail the build, not read as a
        // legitimate rejection.
        assertRuleRejects(layeringRule(), fixtureClasses());
    }

    private static final Set<String> LAYER_SEGMENTS =
            Set.of("domain", "application", "infrastructure", "web");

    /**
     * Package-visible for {@link EmptyShouldExceptionInventoryTest}: {@code true} while the
     * ADR-0018 exception on {@link #productionCodeRespectsLayeringYet()} is still justified,
     * {@code false} the moment any production class lands in a domain, application,
     * infrastructure or web package.
     */
    static boolean noBusinessModuleExistsYet(JavaClasses classes) {
        return classes.stream()
                .map(JavaClass::getPackageName)
                .flatMap(name -> Arrays.stream(name.split("\\.")))
                .noneMatch(LAYER_SEGMENTS::contains);
    }
}
