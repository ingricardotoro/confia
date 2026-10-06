package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.security.SessionValidity;
import com.confia.shared.web.testsupport.fixture.WebEdgeScopeViolationFixtures;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationProvider;

/**
 * The exclusions of web-edge-foundations that this part of the change leaves open, each with the
 * owner that will close it (design.md, decisions 14 and 20; specs/web-edge, requirements "La
 * vigencia de sesión es un puerto...", "Ausencia de autenticación por credencial, tokens y
 * endpoints de identidad", "Ausencia de matriz de roles..." and "Ausencia de DTO de
 * instituciones..."):
 *
 * <ul>
 *   <li>the session validity port has no implementation and no bean, and does not reach {@code
 *       identity} (owner: {@code session-tokens-and-web-layer}, slice C5b);
 *   <li>no class is an {@code AuthenticationProvider} and none uses {@code
 *       jakarta.servlet.http.Cookie} (owner: {@code session-tokens-and-web-layer});
 *   <li>no authorization rule on a route beyond the public allow-list and the final denial, and no
 *       method-level permission annotation (owner: change 8, RBAC);
 *   <li>no class of a {@code ..web..} package has {@code Institution} in its name (owner: the first
 *       endpoint of institution administration).
 * </ul>
 *
 * <p>Static inventories over the compiled class tree, in the manner of {@code
 * IdentityScopeExclusionInventoryTest}. Every absence is asserted on a base set that is proven
 * non-empty first, because an absence over an accidentally empty set would pass without checking
 * anything, and every absence has its own detection fixture, a deliberately violating class of
 * {@link WebEdgeScopeViolationFixtures} that the same detector must find. Each fixture class is
 * imported one at a time: the fixtures are test code, outside the production scope every rule here
 * scans.
 */
class WebEdgeScopeExclusionInventoryTest {

    private static final String AUTHORIZED_URL =
            "org.springframework.security.config.annotation.web.configurers"
                    + ".AuthorizeHttpRequestsConfigurer$AuthorizedUrl";
    private static final Set<String> ALLOWED_ROUTE_RULES = Set.of("permitAll", "denyAll");
    private static final Set<String> PERMISSION_ANNOTATIONS = Set.of(
            "org.springframework.security.access.prepost.PreAuthorize",
            "org.springframework.security.access.prepost.PostAuthorize",
            "org.springframework.security.access.annotation.Secured",
            "jakarta.annotation.security.RolesAllowed");

    // --- The session validity port has no adapter (owner: session-tokens-and-web-layer, C5b) ---

    @Test
    void theSessionValidityPortIsAnInterfaceWithNoDependencyOnIdentityAndNoImplementation() {
        JavaClasses production = assertNonEmptyProductionClasses();

        JavaClass port = production.get(SessionValidity.class);
        assertThat(port.isInterface()).as("the port is an interface").isTrue();
        assertThat(port.getPackageName()).isEqualTo("com.confia.shared.security");
        assertThat(port.getDirectDependenciesFromSelf())
                .as("the port has real dependencies of its own, or the identity check below would "
                        + "pass over nothing")
                .isNotEmpty();
        assertThat(port.getDirectDependenciesFromSelf().stream()
                .map(dependency -> dependency.getTargetClass().getFullName())
                .filter(name -> name.startsWith("com.confia.identity")))
                .as("shared must not depend on identity: the port is what lets identity implement "
                        + "it without the arrow pointing back")
                .isEmpty();

        assertThat(implementationsOf(production, SessionValidity.class))
                .as("no production class implements the session validity port: the "
                        + "implementation is session-tokens-and-web-layer's (slice C5b)")
                .isEmpty();
    }

    @Test
    void theImplementationDetectorFindsAFixtureThatImplementsThePort() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                WebEdgeScopeViolationFixtures.SessionValidityAdapter.class);

        assertThat(implementationsOf(fixtures, SessionValidity.class))
                .containsExactly(WebEdgeScopeViolationFixtures.SessionValidityAdapter.class
                        .getName());
    }

    // --- No credential authentication (owner: session-tokens-and-web-layer) ---

    @Test
    void noProductionClassIsAnAuthenticationProvider() {
        JavaClasses production = assertNonEmptyProductionClasses();

        assertThat(implementationsOf(production, AuthenticationProvider.class))
                .as("no production class authenticates a credential: the authentication filters "
                        + "and providers are session-tokens-and-web-layer's")
                .isEmpty();
    }

    @Test
    void theImplementationDetectorFindsAFixtureThatIsAnAuthenticationProvider() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                WebEdgeScopeViolationFixtures.CredentialAuthenticationProvider.class);

        assertThat(implementationsOf(fixtures, AuthenticationProvider.class))
                .containsExactly(WebEdgeScopeViolationFixtures.CredentialAuthenticationProvider
                        .class.getName());
    }

    @Test
    void noProductionClassUsesTheServletCookie() {
        JavaClasses production = assertNonEmptyProductionClasses();

        assertThat(production.stream().filter(WebEdgeScopeExclusionInventoryTest::dependsOnServlet))
                .as("the scan sees servlet types in production, so the cookie check below is "
                        + "looking at real dependencies")
                .isNotEmpty();
        assertThat(classesUsingTheServletCookie(production))
                .as("no production class creates or reads a session cookie: the cookie is "
                        + "session-tokens-and-web-layer's")
                .isEmpty();
    }

    @Test
    void theCookieDetectorFindsAFixtureThatUsesTheServletCookie() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                WebEdgeScopeViolationFixtures.SessionCookieWriter.class);

        assertThat(classesUsingTheServletCookie(fixtures))
                .containsExactly(WebEdgeScopeViolationFixtures.SessionCookieWriter.class
                        .getName());
    }

    // --- No permission rule on a route (owner: change 8, RBAC) ---

    @Test
    void theChainHoldsOnlyPublicRoutesAndTheFinalDenial() {
        JavaClasses production = assertNonEmptyProductionClasses();

        assertThat(routeRulesApplied(production))
                .as("the scan sees the real chain: its allow-list permits routes and its last "
                        + "rule denies every other one")
                .containsAll(ALLOWED_ROUTE_RULES);
        assertThat(routeRulesBeyondTheAllowListAndTheFinalDenial(production))
                .as("no route carries a role, permission, authentication or MFA rule: the matrix "
                        + "is change 8's")
                .isEmpty();
    }

    @Test
    void theRouteRuleDetectorFindsAFixtureThatRequiresARole() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                WebEdgeScopeViolationFixtures.RoleProtectedChain.class);

        assertThat(routeRulesBeyondTheAllowListAndTheFinalDenial(fixtures))
                .containsExactly(WebEdgeScopeViolationFixtures.RoleProtectedChain.class.getName()
                        + " applies hasRole(...) to a route");
    }

    @Test
    void noProductionMethodOrClassCarriesAPermissionAnnotation() {
        JavaClasses production = assertNonEmptyProductionClasses();

        assertThat(production.stream().flatMap(javaClass -> javaClass.getMethods().stream())
                .filter(method -> method.isAnnotatedWith("org.springframework.context.annotation"
                        + ".Bean")))
                .as("the scan sees real annotations on real methods, so the check below is not "
                        + "looking at an empty set")
                .isNotEmpty();
        assertThat(classesWithAPermissionAnnotation(production))
                .as("no class or method carries a permission annotation: RBAC is change 8's")
                .isEmpty();
    }

    @Test
    void thePermissionAnnotationDetectorFindsFixturesThatCarryEach() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                WebEdgeScopeViolationFixtures.PreAuthorizedOperation.class,
                WebEdgeScopeViolationFixtures.SecuredOperation.class);

        assertThat(classesWithAPermissionAnnotation(fixtures)).containsExactlyInAnyOrder(
                WebEdgeScopeViolationFixtures.PreAuthorizedOperation.class.getName(),
                WebEdgeScopeViolationFixtures.SecuredOperation.class.getName());
    }

    // --- No institution DTO (owner: the first institution administration endpoint) ---

    @Test
    void noWebClassHasInstitutionInItsName() {
        JavaClasses production = assertNonEmptyProductionClasses();

        assertThat(webClassesNamedAfterAnInstitution(production))
                .as("no web class is named after an institution: the DTO with its 200 and 500 "
                        + "character limits comes with the first institution administration "
                        + "endpoint")
                .isEmpty();
    }

    @Test
    void theInstitutionNameDetectorFindsAFixtureInAWebPackage() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                WebEdgeScopeViolationFixtures.InstitutionSummaryDto.class);

        assertThat(webClassesNamedAfterAnInstitution(fixtures))
                .containsExactly(WebEdgeScopeViolationFixtures.InstitutionSummaryDto.class
                        .getName());
    }

    // --- Detectors, each used on production and on its fixture ---

    private static List<String> implementationsOf(JavaClasses classes, Class<?> port) {
        return classes.stream()
                .filter(javaClass -> !javaClass.isInterface())
                .filter(javaClass -> javaClass.isAssignableTo(port))
                .map(JavaClass::getName)
                .sorted()
                .toList();
    }

    private static boolean dependsOnServlet(JavaClass javaClass) {
        return javaClass.getDirectDependenciesFromSelf().stream()
                .map(Dependency::getTargetClass)
                .anyMatch(target -> target.getPackageName().equals("jakarta.servlet.http"));
    }

    private static List<String> classesUsingTheServletCookie(JavaClasses classes) {
        return classes.stream()
                .filter(javaClass -> javaClass.getDirectDependenciesFromSelf().stream()
                        .anyMatch(dependency -> dependency.getTargetClass().getName()
                                .equals("jakarta.servlet.http.Cookie")))
                .map(JavaClass::getName)
                .sorted()
                .toList();
    }

    /** The names of the route rule methods that the classes call on an {@code AuthorizedUrl}. */
    private static Set<String> routeRulesApplied(JavaClasses classes) {
        Set<String> applied = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                if (call.getTargetOwner().getName().equals(AUTHORIZED_URL)) {
                    applied.add(call.getTarget().getName());
                }
            }
        }
        return applied;
    }

    private static List<String> routeRulesBeyondTheAllowListAndTheFinalDenial(
            JavaClasses classes) {
        List<String> found = new java.util.ArrayList<>();
        for (JavaClass javaClass : classes) {
            for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                String rule = call.getTarget().getName();
                if (call.getTargetOwner().getName().equals(AUTHORIZED_URL)
                        && !ALLOWED_ROUTE_RULES.contains(rule)) {
                    found.add(javaClass.getName() + " applies " + rule + "(...) to a route");
                }
            }
        }
        return found.stream().sorted().toList();
    }

    private static List<String> classesWithAPermissionAnnotation(JavaClasses classes) {
        return classes.stream()
                .filter(javaClass -> PERMISSION_ANNOTATIONS.stream()
                                .anyMatch(javaClass::isAnnotatedWith)
                        || javaClass.getMethods().stream().anyMatch(
                                WebEdgeScopeExclusionInventoryTest::hasAPermissionAnnotation))
                .map(JavaClass::getName)
                .sorted()
                .toList();
    }

    private static boolean hasAPermissionAnnotation(JavaMethod method) {
        return PERMISSION_ANNOTATIONS.stream().anyMatch(method::isAnnotatedWith);
    }

    /**
     * The binary name after the package, so a nested class is caught by the name of its outer
     * class too ({@code Outer$InstitutionDto}, {@code InstitutionOuter$Row}).
     */
    private static List<String> webClassesNamedAfterAnInstitution(JavaClasses classes) {
        return classes.stream()
                .filter(javaClass -> ("." + javaClass.getPackageName() + ".").contains(".web."))
                .filter(javaClass -> javaClass.getName()
                        .substring(javaClass.getPackageName().length()).contains("Institution"))
                .map(JavaClass::getName)
                .sorted()
                .toList();
    }

    /** Proves the production base set is real, as the other inventories of this change do. */
    private static JavaClasses assertNonEmptyProductionClasses() {
        JavaClasses classes = productionClasses();
        assertThat(classes.stream().map(JavaClass::getFullName).toList())
                .as("the scanned production class tree must be real and populated, or every "
                        + "absence check in this class would pass vacuously")
                .contains("com.confia.shared.security.SessionValidity",
                        "com.confia.shared.web.edge.SecurityChains",
                        "com.confia.shared.web.problem.ProblemBody");
        return classes;
    }
}
