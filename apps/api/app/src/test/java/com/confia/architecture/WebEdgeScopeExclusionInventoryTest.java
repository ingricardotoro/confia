package com.confia.architecture;

import static com.confia.architecture.ArchitectureTestSupport.productionClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.architecture.fixture.cookie.identity.web.BadControllerUsingCookie;
import com.confia.identity.application.SessionLiveness;
import com.confia.shared.security.RateLimiter;
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
import java.util.function.Predicate;
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
 *   <li>the session validity port has exactly one implementation, in {@code identity}, is asked by
 *       the authentication filter alone, is never returned by a lambda-backed factory and does not
 *       reach {@code identity} (closed by {@code session-tokens-and-web-layer}, task 5.1);
 *   <li>no class is an {@code AuthenticationProvider} (owner: {@code session-tokens-and-web-layer})
 *       and exactly one, the cookie helper {@code SessionCookies}, uses {@code
 *       jakarta.servlet.http.Cookie} (session-endpoints-cookie-and-csrf, BI47);
 *   <li>no authorization rule on a route beyond the public allow-list, the authenticated list (any
 *       authenticated actor, no role) and the final denial, and no
 *       method-level permission annotation (owner: change 8, RBAC);
 *   <li>no class of a {@code ..web..} package has {@code Institution} in its name (owner: the first
 *       endpoint of institution administration);
 *   <li>the limiter and the delay materializer are used by no production class outside their own
 *       packages, {@code shared.web.edge}, which wires them, and the web layer of {@code identity},
 *       whose login endpoint is the first to apply them (condition H1; inverted by
 *       session-endpoints-cookie-and-csrf, build-integrity BI49);
 *   <li>the limiter port has one implementation, the in-memory one, and no Redis client is on the
 *       class path (owner: change 11, containerization-and-cicd-pipeline).
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

    /** The one class that may use the servlet cookie: it writes and reads the session cookies. */
    private static final String COOKIE_HELPER = "com.confia.shared.web.session.SessionCookies";

    private static final String AUTHORIZED_URL =
            "org.springframework.security.config.annotation.web.configurers"
                    + ".AuthorizeHttpRequestsConfigurer$AuthorizedUrl";
    private static final Set<String> ALLOWED_ROUTE_RULES =
            Set.of("permitAll", "authenticated", "denyAll");
    private static final Set<String> PERMISSION_ANNOTATIONS = Set.of(
            "org.springframework.security.access.prepost.PreAuthorize",
            "org.springframework.security.access.prepost.PostAuthorize",
            "org.springframework.security.access.annotation.Secured",
            "jakarta.annotation.security.RolesAllowed",
            "org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity");

    /** The types through which a class applies the limiter, and the packages that may. */
    private static final Set<String> LIMITER_TYPES = Set.of(
            "com.confia.shared.security.RateLimiter",
            "com.confia.shared.web.ratelimit.RateLimited",
            "com.confia.shared.web.ratelimit.RateLimiterRegistry",
            "com.confia.shared.web.ratelimit.RateLimitInterceptor");
    private static final Set<String> LIMITER_PACKAGES = Set.of("com.confia.shared.web.ratelimit",
            "com.confia.shared.security", "com.confia.shared.web.edge", "com.confia.identity.web");

    /** The types through which a class asks for a delay, and the packages that may. */
    private static final Set<String> MATERIALIZER_TYPES = Set.of(
            "com.confia.shared.web.delay.RequiredDelayMaterializer",
            "com.confia.shared.web.delay.Delayed", "com.confia.shared.web.delay.DelayTimer",
            "com.confia.shared.web.delay.DelayProperties");
    private static final Set<String> MATERIALIZER_PACKAGES = Set.of("com.confia.shared.web.delay",
            "com.confia.shared.web.edge", "com.confia.identity.web", "com.confia.identity.web.wiring");

    /** A class of each Redis client library the project could pick, as a class path resource. */
    private static final List<String> REDIS_CLIENT_RESOURCES = List.of(
            "io/lettuce/core/RedisClient.class", "redis/clients/jedis/Jedis.class",
            "org/redisson/Redisson.class",
            "org/springframework/data/redis/core/RedisTemplate.class");
    private static final List<String> REDIS_CLIENT_PACKAGES = List.of("io.lettuce",
            "redis.clients", "org.redisson", "org.springframework.data.redis");

    // --- The session validity port has exactly one adapter, in identity (session-tokens-and-web-layer, 5.1) ---

    private static final String FILTER = "com.confia.shared.web.authentication"
            + ".AccessTokenAuthenticationFilter";

    @Test
    void theSessionValidityPortIsAnInterfaceThatDoesNotDependOnIdentity() {
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
    }

    @Test
    void theSessionValidityPortHasExactlyOneProductionAdapterAndItBelongsToIdentity() {
        JavaClasses production = assertNonEmptyProductionClasses();

        assertThat(implementationsOf(production, SessionValidity.class))
                .containsExactly("com.confia.identity.application.SessionLiveness");
        assertThat(adapterViolations(production)).isEmpty();
    }

    @Test
    void theAdapterDetectorFindsAFixtureThatAddsASecondAdapterAndOneOutsideIdentity() {
        JavaClasses withASecondOne = new ClassFileImporter().importClasses(
                SessionLiveness.class, WebEdgeScopeViolationFixtures.SessionValidityAdapter.class);
        JavaClasses outsideIdentity = new ClassFileImporter().importClasses(
                WebEdgeScopeViolationFixtures.SessionValidityAdapter.class);

        assertThat(implementationsOf(withASecondOne, SessionValidity.class)).hasSize(2);
        assertThat(adapterViolations(withASecondOne))
                .as("two adapters are not exactly one")
                .anyMatch(violation -> violation.contains("exactly one"));
        assertThat(adapterViolations(outsideIdentity))
                .as("an adapter outside identity")
                .anyMatch(violation -> violation.contains("outside com.confia.identity"));
    }

    /**
     * The only production class that calls the port, besides the adapter that implements it, is the
     * authentication filter. The wiring that hands the adapter to the filter holds the port in a
     * parameter and never asks it anything, so it is not a consumer.
     */
    @Test
    void theAuthenticationFilterIsTheOnlyProductionConsumerOfThePort() {
        JavaClasses production = assertNonEmptyProductionClasses();

        assertThat(classesCallingThePort(production))
                .as("the scan sees the call the filter makes, so an empty result elsewhere means "
                        + "nobody else calls")
                .containsExactly(FILTER);
        assertThat(consumerViolations(production)).isEmpty();
    }

    @Test
    void theConsumerDetectorFindsAFixtureThatAsksThePortOutsideTheFilter() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                WebEdgeScopeViolationFixtures.SessionValidityConsumer.class);

        assertThat(consumerViolations(fixtures)).containsExactly(
                WebEdgeScopeViolationFixtures.SessionValidityConsumer.class.getName());
    }

    /**
     * The port has a single abstract method, so a lambda is an adapter that no class declares: a
     * factory method that returns {@code SessionValidity} would be a bean with no implementing class,
     * which the adapter check cannot see. No production method returns the port.
     */
    @Test
    void noProductionMethodReturnsThePortSoNoAdapterHidesInALambda() {
        JavaClasses production = assertNonEmptyProductionClasses();

        assertThat(production.stream().filter(javaClass -> javaClass.getName()
                .equals(SessionValidity.class.getName())))
                .as("the scanned set holds the port itself, so the scan runs over a populated tree")
                .hasSize(1);
        assertThat(classesReturningThePort(production))
                .as("no production class returns the session validity port, which rules out a "
                        + "lambda-backed bean")
                .isEmpty();
    }

    @Test
    void theFactoryDetectorFindsAFixtureThatReturnsALambdaBackedPort() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                WebEdgeScopeViolationFixtures.LambdaSessionValidityFactory.class);

        assertThat(classesReturningThePort(fixtures))
                .containsExactly(WebEdgeScopeViolationFixtures.LambdaSessionValidityFactory
                        .class.getName());
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

    /**
     * The inversion of "no production class uses the servlet cookie" (session-endpoints-cookie-and-
     * csrf, BI47): exactly one class does, the cookie helper, and it is the only one that may.
     */
    @Test
    void onlyTheCookieHelperUsesTheServletCookie() {
        JavaClasses production = assertNonEmptyProductionClasses();

        assertThat(production.stream().filter(WebEdgeScopeExclusionInventoryTest::dependsOnServlet))
                .as("the scan sees servlet types in production, so the cookie check below is "
                        + "looking at real dependencies")
                .isNotEmpty();
        assertThat(classesUsingTheServletCookie(production))
                .as("exactly the cookie helper reads the cookies of a request: the attributes of a "
                        + "cookie are written in one place")
                .containsExactly(COOKIE_HELPER);
        assertThat(classesUsingTheServletCookieOutsideTheHelper(production)).isEmpty();
    }

    @Test
    void aControllerOfIdentityWebThatUsesTheServletCookieIsFlaggedByTheConfinement() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(BadControllerUsingCookie.class);

        assertThat(classesUsingTheServletCookieOutsideTheHelper(fixtures))
                .containsExactly(BadControllerUsingCookie.class.getName());
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
    void theChainHoldsOnlyPublicRoutesAuthenticatedRoutesAndTheFinalDenial() {
        JavaClasses production = assertNonEmptyProductionClasses();

        assertThat(routeRulesApplied(production))
                .as("the scan sees the real chain: its lists permit and authenticate routes and its last "
                        + "rule denies every other one")
                .containsAll(ALLOWED_ROUTE_RULES);
        assertThat(routeRulesBeyondTheAllowListAndTheFinalDenial(production))
                .as("no route carries a role, permission or MFA rule: the matrix "
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
                WebEdgeScopeViolationFixtures.SecuredOperation.class,
                WebEdgeScopeViolationFixtures.ComposedPermissionOperation.class,
                WebEdgeScopeViolationFixtures.MethodSecurityEnabler.class);

        assertThat(classesWithAPermissionAnnotation(fixtures)).containsExactlyInAnyOrder(
                WebEdgeScopeViolationFixtures.PreAuthorizedOperation.class.getName(),
                WebEdgeScopeViolationFixtures.SecuredOperation.class.getName(),
                WebEdgeScopeViolationFixtures.ComposedPermissionOperation.class.getName(),
                WebEdgeScopeViolationFixtures.MethodSecurityEnabler.class.getName());
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

    // --- The limiter and the delay materializer apply to no production endpoint (owner: C6a) ---

    @Test
    void noProductionClassOutsideTheEdgeUsesTheLimiter() {
        JavaClasses production = assertNonEmptyProductionClasses();

        assertThat(usesOf(production, LIMITER_TYPES, Set.of()))
                .as("non-vacuous: the edge configuration and the startup check really use the "
                        + "limiter types, so the check below looks at real dependencies")
                .contains("com.confia.shared.web.edge.ThrottlingConfiguration",
                        "com.confia.shared.web.ratelimit.RateLimitPolicyCheck",
                        "com.confia.identity.web.LoginController");
        assertThat(usesOf(production, LIMITER_TYPES, LIMITER_PACKAGES))
                .as("only the limiter's own packages, the edge and the web layer of identity use it: the "
                        + "login endpoint applies it (condition H1), and no other package does")
                .isEmpty();
    }

    @Test
    void theLimiterDetectorFindsAControllerThatAppliesIt() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                WebEdgeScopeViolationFixtures.LimitedLoginController.class);

        assertThat(usesOf(fixtures, LIMITER_TYPES, LIMITER_PACKAGES)).containsExactly(
                WebEdgeScopeViolationFixtures.LimitedLoginController.class.getName());
    }

    @Test
    void noProductionClassOutsideTheEdgeUsesTheDelayMaterializer() {
        JavaClasses production = assertNonEmptyProductionClasses();

        assertThat(usesOf(production, MATERIALIZER_TYPES, Set.of()))
                .as("non-vacuous: the edge configuration really uses the materializer types")
                .contains("com.confia.shared.web.edge.RequiredDelayConfiguration",
                        "com.confia.identity.web.LoginController");
        assertThat(usesOf(production, MATERIALIZER_TYPES, MATERIALIZER_PACKAGES))
                .as("only the materializer's own package, the edge and the web layer of identity (the login "
                        + "endpoint and the configuration that registers it) use it, and no other does")
                .isEmpty();
    }

    @Test
    void theMaterializerDetectorFindsAControllerThatAsksForADelay() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                WebEdgeScopeViolationFixtures.DelayingLoginController.class);

        assertThat(usesOf(fixtures, MATERIALIZER_TYPES, MATERIALIZER_PACKAGES)).containsExactly(
                WebEdgeScopeViolationFixtures.DelayingLoginController.class.getName());
    }

    // --- One limiter implementation and no Redis client (owner: change 11) ---

    @Test
    void theLimiterPortHasASingleImplementationAndItIsInMemory() {
        JavaClasses production = assertNonEmptyProductionClasses();

        assertThat(production.get(RateLimiter.class).isInterface()).as("the port is an interface")
                .isTrue();
        assertThat(implementationsOf(production, RateLimiter.class))
                .as("the in-memory limiter is the only implementation: the Redis adapter that "
                        + "implements the same port is change 11's")
                .containsExactly("com.confia.shared.security.InMemoryRateLimiter");
    }

    @Test
    void theImplementationDetectorFindsASecondLimiter() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(
                WebEdgeScopeViolationFixtures.SecondRateLimiter.class);

        assertThat(implementationsOf(fixtures, RateLimiter.class)).containsExactly(
                WebEdgeScopeViolationFixtures.SecondRateLimiter.class.getName());
    }

    @Test
    void noRedisClientIsOnTheClassPathAndNoProductionClassDependsOnOne() {
        JavaClasses production = assertNonEmptyProductionClasses();
        Predicate<String> onTheClassPath =
                resource -> WebEdgeScopeExclusionInventoryTest.class.getClassLoader()
                        .getResource(resource) != null;

        assertThat(onTheClassPath.test("org/springframework/web/servlet/DispatcherServlet.class"))
                .as("non-vacuous: the lookup finds a class that really is on the class path")
                .isTrue();
        assertThat(redisClientsFound(onTheClassPath))
                .as("no Redis client is on the class path: the adapter that shares the limiter "
                        + "between processes is change 11's")
                .isEmpty();
        assertThat(classesDependingOnARedisClient(production)).isEmpty();
    }

    @Test
    void theRedisDetectorsFindAClientOnTheClassPathAndADependencyOnOne() {
        assertThat(redisClientsFound(resource -> resource.equals("redis/clients/jedis/Jedis.class")))
                .containsExactly("redis/clients/jedis/Jedis.class");
        assertThat(isARedisClientPackage("io.lettuce.core")).isTrue();
        assertThat(isARedisClientPackage("org.springframework.data.redis.core")).isTrue();
        assertThat(isARedisClientPackage("org.redisson")).isTrue();
        assertThat(isARedisClientPackage("com.confia.shared.web.delay")).isFalse();
    }

    // --- Detectors, each used on production and on its fixture ---

    /**
     * The classes outside {@code allowedPackages} (exact package names) that depend on any of
     * {@code types}: through a field, a parameter, a return type, a call or an annotation. The
     * types themselves are skipped, and so is any class that belongs to an allowed package.
     */
    private static List<String> usesOf(JavaClasses classes, Set<String> types,
            Set<String> allowedPackages) {
        return classes.stream()
                .filter(javaClass -> !allowedPackages.contains(javaClass.getPackageName()))
                .filter(javaClass -> !types.contains(javaClass.getName()))
                .filter(javaClass -> javaClass.getDirectDependenciesFromSelf().stream()
                        .anyMatch(dependency -> types.contains(
                                dependency.getTargetClass().getName())))
                .map(JavaClass::getName)
                .sorted()
                .toList();
    }

    private static List<String> redisClientsFound(Predicate<String> onTheClassPath) {
        return REDIS_CLIENT_RESOURCES.stream().filter(onTheClassPath).sorted().toList();
    }

    private static boolean isARedisClientPackage(String packageName) {
        return REDIS_CLIENT_PACKAGES.stream().anyMatch(
                candidate -> packageName.equals(candidate) || packageName.startsWith(candidate + "."));
    }

    private static List<String> classesDependingOnARedisClient(JavaClasses classes) {
        return classes.stream()
                .filter(javaClass -> javaClass.getDirectDependenciesFromSelf().stream()
                        .anyMatch(dependency -> isARedisClientPackage(
                                dependency.getTargetClass().getPackageName())))
                .map(JavaClass::getName)
                .sorted()
                .toList();
    }

    private static List<String> implementationsOf(JavaClasses classes, Class<?> port) {
        return classes.stream()
                .filter(javaClass -> !javaClass.isInterface())
                .filter(javaClass -> javaClass.isAssignableTo(port))
                .map(JavaClass::getName)
                .sorted()
                .toList();
    }

    /** Why the classes do not hold exactly one adapter of the port, and in {@code identity}. */
    private static List<String> adapterViolations(JavaClasses classes) {
        List<String> adapters = implementationsOf(classes, SessionValidity.class);
        List<String> violations = new java.util.ArrayList<>();
        if (adapters.size() != 1) {
            violations.add("expected exactly one adapter of the port and found " + adapters);
        }
        adapters.stream().filter(name -> !name.startsWith("com.confia.identity."))
                .forEach(name -> violations.add(name + " is outside com.confia.identity"));
        return violations;
    }

    /** The classes, other than the port, that call the method of the port. */
    private static List<String> classesCallingThePort(JavaClasses classes) {
        return classes.stream()
                .filter(javaClass -> !javaClass.getName().equals(SessionValidity.class.getName()))
                .filter(javaClass -> javaClass.getMethodCallsFromSelf().stream()
                        .anyMatch(call -> call.getTargetOwner().getName()
                                .equals(SessionValidity.class.getName())))
                .map(JavaClass::getName)
                .sorted()
                .toList();
    }

    /** The classes that call the port without being the authentication filter. */
    private static List<String> consumerViolations(JavaClasses classes) {
        return classesCallingThePort(classes).stream()
                .filter(name -> !name.equals(FILTER))
                .toList();
    }

    private static List<String> classesReturningThePort(JavaClasses classes) {
        return classes.stream()
                .filter(javaClass -> javaClass.getMethods().stream().anyMatch(method ->
                        method.getRawReturnType().getName().equals(SessionValidity.class.getName())
                                && !method.getOwner().isInterface()))
                .map(JavaClass::getName)
                .sorted()
                .toList();
    }

    private static boolean dependsOnServlet(JavaClass javaClass) {
        return javaClass.getDirectDependenciesFromSelf().stream()
                .map(Dependency::getTargetClass)
                .anyMatch(target -> target.getPackageName().equals("jakarta.servlet.http"));
    }

    private static List<String> classesUsingTheServletCookieOutsideTheHelper(JavaClasses classes) {
        return classesUsingTheServletCookie(classes).stream()
                .filter(name -> !name.equals(COOKIE_HELPER)).toList();
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
                                .anyMatch(javaClass::isMetaAnnotatedWith)
                        || javaClass.getMethods().stream().anyMatch(
                                WebEdgeScopeExclusionInventoryTest::hasAPermissionAnnotation))
                .map(JavaClass::getName)
                .sorted()
                .toList();
    }

    private static boolean hasAPermissionAnnotation(JavaMethod method) {
        return PERMISSION_ANNOTATIONS.stream().anyMatch(method::isMetaAnnotatedWith);
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
