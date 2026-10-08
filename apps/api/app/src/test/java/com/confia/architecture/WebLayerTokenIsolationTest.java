package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.assertRuleRejects;
import static com.confia.architecture.ArchitectureTestSupport.fixtureClasses;
import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Rule BI14 and BI15 (session-tokens-and-web-layer design.md, decision 17; specs/build-integrity,
 * requirement "Ninguna clase de la capa web depende del códec, del verificador, del anillo de claves
 * ni del emisor de tokens"): no production class of a {@code web} package, the one of a business
 * module or {@code com.confia.shared.web}, depends on anything in {@code
 * com.confia.shared.security.token}, with exactly two exceptions: the authentication filter of the
 * edge and the configuration of the administrative chain that builds it. A controller gets the actor
 * from the principal that filter already authenticated and never reads a token.
 *
 * <p>Both halves of the repository's two-half convention (ADR-0018). The production half evaluates
 * the real web classes and carries no empty-set exception. The fixture half must reject {@code
 * BadWebClassUsingVerifier} for each of the four named types. The two exempt classes are named by
 * their complete names and, as inner classes included, by nothing else: a third class, or the same
 * simple name in another package, is not exempt. The filter arrived with task 2.1; it was named
 * here before it existed so the rule needed no edit then, and {@code PENDING_EXEMPT_CLASSES} plus the test that checks
 * every exempt name keeps a typo from standing as a silent exemption.
 */
class WebLayerTokenIsolationTest {

    static final String TOKEN_PACKAGE = "com.confia.shared.security.token..";
    static final List<String> EXEMPT_CLASSES = List.of(
            "com.confia.shared.web.authentication.AccessTokenAuthenticationFilter",
            "com.confia.shared.web.edge.AdminSecurityConfiguration");

    /**
     * Exempt classes that do not exist yet, each with the task that creates it. An exempt name that
     * is neither a real class nor listed here is a typo, and a typo would leave a real class
     * unexempt or an exemption pointing at nothing; the test below fails on it. Remove an entry when
     * its class arrives: the same test fails while a listed class already exists.
     */
    static final List<String> PENDING_EXEMPT_CLASSES = List.of();

    private static final DescribedPredicate<JavaClass> NOT_EXEMPT =
            new DescribedPredicate<>("not the authentication filter or the administrative chain "
                    + "configuration") {
                @Override
                public boolean test(JavaClass javaClass) {
                    return !isExempt(javaClass.getName());
                }
            };

    private static final ArchRule RULE = noClasses()
            .that().resideInAPackage("..web..").and(NOT_EXEMPT)
            .should().dependOnClassesThat().resideInAPackage(TOKEN_PACKAGE)
            .because("a web class never interprets a token: the authentication filter verifies it "
                    + "and everything after it reads the principal (session-tokens-and-web-layer "
                    + "design.md, decision 17)");

    @Test
    void productionWebClassesNeverDependOnTheCodecTheVerifierTheKeyRingOrTheIssuer() {
        RULE.check(productionClasses());
    }

    @Test
    void rejectsTheFixtureWebClassThatVerifiesATokenItself() {
        assertRuleRejects(RULE, fixtureClasses(), "BadWebClassUsingVerifier",
                "com.confia.shared.security.token.AccessTokenVerifier",
                "com.confia.shared.security.token.CompactJws",
                "com.confia.shared.security.token.SigningKeyRing",
                "com.confia.shared.security.token.AccessTokenIssuer");
    }

    /** Non-vacuity: the rule evaluates real web classes of the shared edge, not an empty set. */
    @Test
    void theRuleEvaluatesRealProductionWebClasses() {
        List<String> webClasses = productionClasses().stream().map(JavaClass::getName)
                .filter(name -> name.contains(".web.")).toList();

        assertThat(webClasses).contains("com.confia.shared.web.edge.AdminSecurityConfiguration",
                "com.confia.shared.web.problem.ProblemBody");
    }

    /** The exemption is by complete class name: an inner class follows its outer, nothing else does. */
    @Test
    void theExemptionNamesTheTwoClassesCompletelyAndNothingElse() {
        assertThat(EXEMPT_CLASSES).hasSize(2);
        assertThat(isExempt("com.confia.shared.web.authentication.AccessTokenAuthenticationFilter"))
                .isTrue();
        assertThat(isExempt("com.confia.shared.web.edge.AdminSecurityConfiguration$Inner")).isTrue();
        assertThat(isExempt("com.confia.shared.web.edge.PortalSecurityConfiguration")).isFalse();
        assertThat(isExempt("com.confia.other.web.edge.AdminSecurityConfiguration")).isFalse();
        assertThat(isExempt("com.confia.shared.web.edge.AdminSecurityConfigurationX")).isFalse();
        assertThat(isExempt("com.confia.identity.web.CurrentSessionController")).isFalse();
    }

    @Test
    void everyExemptNameIsARealClassOrIsListedAsPending() {
        List<String> existing = productionClasses().stream().map(JavaClass::getName).toList();

        List<String> unknown = EXEMPT_CLASSES.stream()
                .filter(name -> !existing.contains(name) && !PENDING_EXEMPT_CLASSES.contains(name))
                .toList();
        List<String> pendingButPresent = PENDING_EXEMPT_CLASSES.stream().filter(existing::contains)
                .toList();

        assertThat(unknown).as("exempt names that are neither real nor pending").isEmpty();
        assertThat(pendingButPresent).as("pending names whose class already exists").isEmpty();
        assertThat(EXEMPT_CLASSES).containsAll(PENDING_EXEMPT_CLASSES);
    }

    private static boolean isExempt(String className) {
        return EXEMPT_CLASSES.stream()
                .anyMatch(exempt -> className.equals(exempt) || className.startsWith(exempt + "$"));
    }
}
