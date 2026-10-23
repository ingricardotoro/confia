package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.ratelimit.RateLimited;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * Scenario [BI45] of specs/build-integrity and condition H1 (session-endpoints-cookie-and-csrf
 * design.md decisions 11 and 20; docs/03-seguridad.md section 4.4): every handler of the web layer
 * of {@code identity} that changes state on a session route declares its rate-limit policy, so the
 * login cannot be exposed without the limiter, and neither can any route added after it.
 *
 * <p>A rule with the convention of two halves. The first half evaluates the real handlers and is
 * never an empty set: ArchUnit refuses to pass over no methods, and the test names the login handler
 * it must have found. The second half imports a permanent fixture, a handler of a package named
 * like the real one that declares no policy, and requires the same rule to reject it naming the
 * class.
 *
 * <p>Reads (a {@code GET}) are not state changes and carry their own decision: the current-session
 * read has no limiter on purpose (its Javadoc states why).
 */
class SessionRoutesRateLimitedRuleTest {

    private static final DescribedPredicate<JavaMethod> CHANGES_STATE = new DescribedPredicate<>(
            "map a request that changes state (POST, PUT, PATCH or DELETE)") {
        @Override
        public boolean test(JavaMethod method) {
            return method.isAnnotatedWith(PostMapping.class) || method.isAnnotatedWith(PutMapping.class)
                    || method.isAnnotatedWith(PatchMapping.class)
                    || method.isAnnotatedWith(DeleteMapping.class);
        }
    };

    private static final ArchRule RULE = methods()
            .that().areDeclaredInClassesThat().resideInAPackage("..identity.web..")
            .and(CHANGES_STATE)
            .should().beAnnotatedWith(RateLimited.class)
            .because("every state-changing handler of a session route declares its rate-limit "
                    + "policy (condition H1, docs/03-seguridad.md section 4.4)");

    @Test
    void everyStateChangingHandlerOfIdentityWebDeclaresItsPolicy() {
        JavaClasses production = productionClasses();

        RULE.check(production);
    }

    @Test
    void theRuleEvaluatesTheRealLoginHandlerAndNotAnEmptySet() {
        JavaClasses production = productionClasses();

        assertThat(production.get("com.confia.identity.web.LoginController").getMethods().stream()
                .filter(CHANGES_STATE))
                .as("the login handler is among the methods the rule evaluates")
                .extracting(JavaMethod::getName).containsExactly("login");
        assertThat(production.get("com.confia.identity.web.LoginController").getMethods().stream()
                .filter(method -> method.getName().equals("login")).findFirst().orElseThrow()
                .getAnnotationOfType(RateLimited.class).policy()).isEqualTo("admin-login");
    }

    @Test
    void theRuleEvaluatesTheTwoSecondFactorHandlersAndBothDeclareTheMfaPolicy() {
        JavaClasses production = productionClasses();

        assertThat(production.get("com.confia.identity.web.SecondFactorController").getMethods().stream()
                .filter(CHANGES_STATE)).extracting(JavaMethod::getName)
                .containsExactlyInAnyOrder("verify", "enroll");
        production.get("com.confia.identity.web.SecondFactorController").getMethods().stream()
                .filter(CHANGES_STATE).forEach(method -> assertThat(
                        method.getAnnotationOfType(RateLimited.class).policy()).isEqualTo("admin-mfa"));
    }

    @Test
    void theRuleRejectsAHandlerOfIdentityWebThatDeclaresNoPolicy() {
        assertRuleRejects(RULE, fixtureClasses(), "BadLoginHandlerWithoutPolicy.login");
    }
}
