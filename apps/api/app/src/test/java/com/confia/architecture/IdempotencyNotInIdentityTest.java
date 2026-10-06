package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Rule W5 (web-edge-foundations design.md, decisions 19 and 20; specs/build-integrity, requirement
 * "El mecanismo HTTP de idempotencia no se aplica a ningún endpoint de identidad"): no class of the
 * {@code identity} module depends on the HTTP idempotency mechanism. {@code IdempotentExecutor}
 * persists and replays the body of a response, so a sign-in, refresh, MFA or recovery endpoint under
 * it would leave its tokens stored in the clear (CLAUDE.md, rules 11 and 13). The same package may
 * be referenced from outside only by the configuration of the web edge that registers it.
 *
 * <p>Both halves of the repository's two-half convention (ADR-0018). The production half has no
 * empty-set exception: it looks at every production class, {@link
 * #theEdgeConfigurationReallyReferencesTheMechanism()} proves the confinement rule is spent on a
 * real reference, and identity has no use. The fixture half must reject {@code
 * BadIdempotentLoginController}, which sits under {@code architecture.fixture.identity}: the one
 * package this rule treats as simulating {@code com.confia.identity}, so a real class never
 * becomes the fixture.
 */
class IdempotencyNotInIdentityTest {

    private static final String MECHANISM_PACKAGE = "com.confia.shared.web.idempotency";
    private static final String EDGE_PACKAGE = "com.confia.shared.web.edge";
    private static final String IDENTITY_PACKAGE = "com.confia.identity";
    private static final String SIMULATED_IDENTITY_FIXTURE_PACKAGE =
            "com.confia.architecture.fixture.identity";

    private static final ArchRule IDENTITY_RULE = classes()
            .should(notDependOnTheMechanism(IdempotencyNotInIdentityTest::isIdentityScoped,
                    "a class of com.confia.identity.."))
            .because("the idempotency mechanism stores and replays response bodies, so tokens "
                    + "would be stored in the clear (decision D3; CLAUDE.md, rules 11 and 13)");

    private static final ArchRule CONFINEMENT_RULE = classes()
            .should(notDependOnTheMechanism(
                    javaClass -> !isIn(javaClass, MECHANISM_PACKAGE)
                            && !isIn(javaClass, EDGE_PACKAGE),
                    "a class outside the mechanism and the web edge configuration"))
            .because("only shared.web.edge registers the mechanism, and the only endpoint that "
                    + "applies it lives in the test tree (web-edge-foundations design.md, "
                    + "decision 19)");

    @Test
    void productionIdentityNeverUsesTheMechanism() {
        IDENTITY_RULE.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureSignInEndpointThatAppliesTheMechanism() {
        assertRuleRejects(IDENTITY_RULE, fixtureClasses(), "BadIdempotentLoginController",
                "IdempotentWrite", "com.confia.shared.web.idempotency");
    }

    @Test
    void onlyTheEdgeConfigurationReferencesTheMechanismFromOutsideItsPackage() {
        CONFINEMENT_RULE.check(productionClasses());
    }

    @Test
    void theConfinementRuleAlsoRejectsTheFixture() {
        assertRuleRejects(CONFINEMENT_RULE, fixtureClasses(), "BadIdempotentLoginController",
                "IdempotentWrite");
    }

    /** Non-vacuity: the one permitted reference is real, so the rule above looked at something. */
    @Test
    void theEdgeConfigurationReallyReferencesTheMechanism() {
        JavaClass configuration = productionClasses()
                .get("com.confia.shared.web.edge.IdempotencyEdgeConfiguration");

        assertThat(configuration.getDirectDependenciesFromSelf())
                .extracting(dependency -> dependency.getTargetClass().getPackageName())
                .contains(MECHANISM_PACKAGE);
        assertThat(productionClasses().stream().filter(c -> isIn(c, IDENTITY_PACKAGE)).count())
                .as("the identity scope holds real production classes").isPositive();
    }

    private static ArchCondition<JavaClass> notDependOnTheMechanism(Predicate<JavaClass> scope,
            String scopeText) {
        return new ArchCondition<>("not depend on " + MECHANISM_PACKAGE + " from " + scopeText) {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (!scope.test(javaClass)) {
                    return;
                }
                for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                    if (dependency.getTargetClass().getPackageName().equals(MECHANISM_PACKAGE)) {
                        events.add(SimpleConditionEvent.violated(javaClass,
                                javaClass.getFullName() + " depends on "
                                        + dependency.getTargetClass().getFullName() + " ("
                                        + MECHANISM_PACKAGE + "), which " + scopeText
                                        + " must not use"));
                    }
                }
            }
        };
    }

    private static boolean isIdentityScoped(JavaClass javaClass) {
        return isIn(javaClass, IDENTITY_PACKAGE)
                || isIn(javaClass, SIMULATED_IDENTITY_FIXTURE_PACKAGE);
    }

    private static boolean isIn(JavaClass javaClass, String packageName) {
        String actual = javaClass.getPackageName();
        return actual.equals(packageName) || actual.startsWith(packageName + ".");
    }
}
