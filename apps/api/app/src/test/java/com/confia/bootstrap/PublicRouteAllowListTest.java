package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.bootstrap.RegisteredRoutes.Route;
import com.confia.shared.web.authentication.AuthenticatedEndpoint;
import com.confia.shared.web.authentication.AuthenticatedEndpoints;
import com.confia.shared.security.SecondFactorPurpose;
import com.confia.shared.web.authentication.SecondFactorEndpoint;
import com.confia.shared.web.authentication.SessionCheck;
import com.confia.shared.web.edge.PublicEndpoint;
import com.confia.shared.web.edge.PublicEndpoints;
import com.confia.shared.web.harness.HarnessProcess;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Specs/web-edge, "La lista blanca pública es cerrada" and "Ausencia de autenticación por
 * credencial y tokens" (web-edge-foundations design.md, decision 21). For every route a started
 * process registers, an anonymous request must be answered {@code 401}, unless the route is on the
 * allow-list; and every entry of the allow-list must be a route the process registers. A new
 * public route therefore needs an edit of {@code PublicEndpoints}, visible in review. The
 * production configuration (no Spring profile) registers no route in the portal and, in the
 * administrative process, only the authenticated route of the current session
 * (session-tokens-and-web-layer, design.md decision 13). Since that change the enumerator also
 * distinguishes the two lists (BI20): an authenticated entry must be served by a route and must
 * answer {@code 401} to an anonymous request, and in production no route is on neither list.
 */
class PublicRouteAllowListTest {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** The routes that answer an anonymous request with anything but {@code 401}, unlisted. */
    static List<String> leaks(ApplicationContext context, int port, PublicEndpoints allowed) {
        List<String> leaks = new ArrayList<>();
        for (Route route : RegisteredRoutes.of(context)) {
            if (RegisteredRoutes.FUNCTIONAL_ROUTER.equals(route.pattern())) {
                continue; // opaque: reported by unenumerableRoutes, there is no path to call
            }
            String method = RegisteredRoutes.ANY_METHOD.equals(route.method()) ? "GET"
                    : route.method();
            int status = anonymous(method, sample(route.pattern()), port);
            if (status != 401 && !isListed(method, sample(route.pattern()), allowed)) {
                leaks.add(method + " " + route.pattern() + " answered " + status
                        + " to an anonymous request and is not on the allow-list");
            }
        }
        return leaks;
    }

    /** The allow-list entries with no registered route that serves them. */
    static List<String> entriesWithoutARoute(ApplicationContext context, PublicEndpoints allowed) {
        List<Route> routes = RegisteredRoutes.of(context);
        List<String> orphans = new ArrayList<>();
        for (PublicEndpoint entry : allowed.endpoints()) {
            boolean served = routes.stream().anyMatch(route -> route.pattern()
                    .equals(entry.pattern()) && (RegisteredRoutes.ANY_METHOD.equals(route.method())
                    || route.method().equals(entry.method().name())));
            if (!served) {
                orphans.add(entry.method() + " " + entry.pattern()
                        + " is allow-listed and no route serves it");
            }
        }
        return orphans;
    }

    /** The authenticated entries with no registered route that serves them (BI20). */
    static List<String> authenticatedEntriesWithoutARoute(ApplicationContext context,
            AuthenticatedEndpoints authenticated) {
        List<Route> routes = RegisteredRoutes.of(context);
        List<String> orphans = new ArrayList<>();
        for (AuthenticatedEndpoint entry : authenticated.endpoints()) {
            boolean served = routes.stream().anyMatch(route -> route.pattern()
                    .equals(entry.pattern()) && (RegisteredRoutes.ANY_METHOD.equals(route.method())
                    || route.method().equals(entry.method().name())));
            if (!served) {
                orphans.add(entry.method() + " " + entry.pattern()
                        + " is on the authenticated list and no route serves it");
            }
        }
        return orphans;
    }

    /**
     * The authenticated entries that answer an anonymous request with anything but {@code 401}
     * (BI20): a route on the authenticated list that runs without a credential is a hole, whatever
     * its controller does.
     */
    static List<String> authenticatedRoutesThatAnswerWithoutACredential(int port,
            AuthenticatedEndpoints authenticated) {
        List<String> holes = new ArrayList<>();
        for (AuthenticatedEndpoint entry : authenticated.endpoints()) {
            int status = anonymous(entry.method().name(), sample(entry.pattern()), port);
            if (status != 401) {
                holes.add(entry.method() + " " + entry.pattern() + " is on the authenticated list "
                        + "and answered " + status + " to an anonymous request");
            }
        }
        return holes;
    }


    /** The restricted entries with no registered route that serves them (decision 6, BI20). */
    static List<String> secondFactorEntriesWithoutARoute(ApplicationContext context,
            AuthenticatedEndpoints endpoints) {
        List<Route> routes = RegisteredRoutes.of(context);
        List<String> orphans = new ArrayList<>();
        for (SecondFactorEndpoint entry : endpoints.secondFactorEndpoints()) {
            boolean served = routes.stream().anyMatch(route -> route.pattern()
                    .equals(entry.pattern()) && (RegisteredRoutes.ANY_METHOD.equals(route.method())
                    || route.method().equals(entry.method().name())));
            if (!served) {
                orphans.add(entry.method() + " " + entry.pattern()
                        + " is on the restricted list and no route serves it");
            }
        }
        return orphans;
    }

    /** The restricted entries that answer an anonymous request with anything but {@code 401}. */
    static List<String> secondFactorRoutesThatAnswerWithoutACredential(int port,
            AuthenticatedEndpoints endpoints) {
        List<String> holes = new ArrayList<>();
        for (SecondFactorEndpoint entry : endpoints.secondFactorEndpoints()) {
            int status = anonymous(entry.method().name(), sample(entry.pattern()), port);
            if (status != 401) {
                holes.add(entry.method() + " " + entry.pattern() + " is on the restricted list "
                        + "and answered " + status + " to an anonymous request");
            }
        }
        return holes;
    }

    /**
     * The registered routes outside the restricted class that do not answer {@code 401} to a request
     * that carries {@code restrictedToken}, a valid one (WE68): a route that accepts a restricted
     * token without being on the list of the class is a way around the class. The filter refuses the
     * token by its audience everywhere else, so every other route answers {@code 401} whether or not
     * a controller serves it; one that does not is named.
     */
    static List<String> routesThatAcceptARestrictedTokenOutsideTheClass(ApplicationContext context,
            int port, AuthenticatedEndpoints endpoints, String restrictedToken) {
        List<String> accepted = new ArrayList<>();
        for (Route route : RegisteredRoutes.of(context)) {
            if (RegisteredRoutes.FUNCTIONAL_ROUTER.equals(route.pattern())) {
                continue;
            }
            String method = RegisteredRoutes.ANY_METHOD.equals(route.method()) ? "POST"
                    : route.method();
            boolean onTheList = endpoints.secondFactorEndpoints().stream().anyMatch(entry ->
                    entry.pattern().equals(route.pattern()) && entry.method().name().equals(method));
            if (onTheList) {
                continue;
            }
            int status = authenticatedRequest(method, sample(route.pattern()), port,
                    restrictedToken);
            if (status != 401) {
                accepted.add(method + " " + route.pattern() + " answered " + status
                        + " to a restricted token and is not on the restricted list");
            }
        }
        return accepted;
    }
    /**
     * The registered routes that are on none of the three lists (BI20 and the decision 13 inversion: the
     * routes of a process are exactly the public ones, the authenticated ones and the restricted ones).
     */
    static List<Route> routesOnNeitherList(ApplicationContext context, PublicEndpoints allowed,
            AuthenticatedEndpoints authenticated) {
        return RegisteredRoutes.of(context).stream()
                .filter(route -> allowed.endpoints().stream().noneMatch(entry ->
                        entry.pattern().equals(route.pattern()) && (RegisteredRoutes.ANY_METHOD
                                .equals(route.method()) || route.method().equals(
                                        entry.method().name()))))
                .filter(route -> authenticated.endpoints().stream().noneMatch(entry ->
                        entry.pattern().equals(route.pattern()) && (RegisteredRoutes.ANY_METHOD
                                .equals(route.method()) || route.method().equals(
                                        entry.method().name()))))
                .filter(route -> authenticated.secondFactorEndpoints().stream().noneMatch(entry ->
                        entry.pattern().equals(route.pattern()) && (RegisteredRoutes.ANY_METHOD
                                .equals(route.method()) || route.method().equals(
                                        entry.method().name()))))
                .toList();
    }

    /**
     * The routes on neither list that do not answer {@code 403} to a request that carries {@code
     * bearerToken}, a valid one: an authenticated actor on a route nobody listed is denied, not
     * served.
     */
    static List<String> routesOnNeitherListThatServeAnAuthenticatedActor(ApplicationContext context,
            int port, PublicEndpoints allowed, AuthenticatedEndpoints authenticated,
            String bearerToken) {
        List<String> served = new ArrayList<>();
        for (Route route : routesOnNeitherList(context, allowed, authenticated)) {
            if (RegisteredRoutes.FUNCTIONAL_ROUTER.equals(route.pattern())) {
                continue;
            }
            String method = RegisteredRoutes.ANY_METHOD.equals(route.method()) ? "GET"
                    : route.method();
            int status = authenticatedRequest(method, sample(route.pattern()), port, bearerToken);
            if (status != 403) {
                served.add(method + " " + route.pattern() + " answered " + status
                        + " to an authenticated actor and is on neither list");
            }
        }
        return served;
    }

    /**
     * A functional router is registered as one opaque entry, so neither the anonymous request nor
     * the snapshot can see what it serves. Until its predicates are enumerated, any is a failure.
     */
    static List<String> unenumerableRoutes(ApplicationContext context) {
        return RegisteredRoutes.of(context).stream()
                .filter(route -> RegisteredRoutes.FUNCTIONAL_ROUTER.equals(route.pattern()))
                .map(route -> RegisteredRoutes.FUNCTIONAL_ROUTER + " is registered: functional "
                        + "routes must be enumerated before they are allowed")
                .toList();
    }

    /** Whether an entry of {@code allowed} admits {@code method} on the concrete {@code path}. */
    private static boolean isListed(String method, String path, PublicEndpoints allowed) {
        return allowed.endpoints().stream().anyMatch(entry -> entry.method().name().equals(method)
                && PathPatternParser.defaultInstance.parse(entry.pattern())
                        .matches(PathContainer.parsePath(path)));
    }

    /** A concrete path for a pattern: every wildcard and variable becomes {@code sample}. */
    static String sample(String pattern) {
        return pattern.replaceAll("\\{[^}]*}", "sample").replace("**", "sample")
                .replace("*", "sample");
    }

    private static int anonymous(String method, String path, int port) {
        return authenticatedRequest(method, path, port, null);
    }

    private static int authenticatedRequest(String method, String path, int port,
            String bearerToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (!"GET".equals(method) && !"HEAD".equals(method) && !"OPTIONS".equals(method)) {
            // The Origin of the harness: the CSRF filter refuses an unsafe method without an allowed one
            // before the token is read, which would hide what these probes look for.
            builder.header("Origin", HarnessProcess.ALLOWED_ORIGIN);
        }
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        HttpRequest request = builder.build();
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling " + path, e);
        }
    }

    private static PublicEndpoints allowListOf(String process, Environment environment) {
        return "admin".equals(process) ? PublicEndpoints.forAdmin(environment)
                : PublicEndpoints.forPortal(environment);
    }

    @ParameterizedTest
    @CsvSource({"admin, default", "portal, default", "admin, local", "portal, local"})
    void everyRegisteredRouteIsDeniedToAnAnonymousRequestUnlessItIsListedAndEveryEntryIsServed(
            String process, String profile) {
        try (OpenApiProcess running = "default".equals(profile) ? OpenApiProcess.start(process)
                : OpenApiProcess.start(process, profile)) {
            ApplicationContext context = running.context();
            PublicEndpoints allowed = allowListOf(process, context.getEnvironment());
            List<Route> routes = RegisteredRoutes.of(context);

            if ("default".equals(profile)) {
                assertThat(routes).as("production registers only the public and the authenticated "
                        + "routes of %s", process).extracting(Route::toString)
                        .containsExactlyInAnyOrderElementsOf(productionRoutesOf(process));
                assertThat(allowed.endpoints()).as("the one public route of production")
                        .extracting(entry -> entry.method() + " " + entry.pattern())
                        .containsExactlyElementsOf(publicRoutesOf(process));
            } else {
                assertThat(routes).as("non-vacuous: springdoc registers its routes in local")
                        .isNotEmpty();
                assertThat(allowed.endpoints()).as("non-vacuous: and lists them").isNotEmpty();
            }
            AuthenticatedEndpoints authenticated = authenticatedListOf(process);
            assertThat(unenumerableRoutes(context)).isEmpty();
            assertThat(leaks(context, running.port(), allowed)).isEmpty();
            assertThat(entriesWithoutARoute(context, allowed)).isEmpty();
            assertThat(authenticatedEntriesWithoutARoute(context, authenticated)).isEmpty();
            assertThat(secondFactorEntriesWithoutARoute(context, authenticated)).isEmpty();
            assertThat(secondFactorRoutesThatAnswerWithoutACredential(running.port(), authenticated))
                    .isEmpty();
            assertThat(authenticatedRoutesThatAnswerWithoutACredential(running.port(),
                    authenticated)).isEmpty();
            if ("default".equals(profile)) {
                assertThat(routesOnNeitherList(context, allowed, authenticated))
                        .as("in production the routes of a process are exactly its public ones, "
                                + "its authenticated ones and its restricted ones").isEmpty();
            }
            // The local profile also registers routes of springdoc that no list admits (the YAML
            // document, the per-group documents). The chain denies them, which the anonymous check
            // above proves, so they are not a hole and the exact-lists check is for production.
        }
    }

    private static AuthenticatedEndpoints authenticatedListOf(String process) {
        return "admin".equals(process) ? AuthenticatedEndpoints.forAdmin()
                : new AuthenticatedEndpoints(List.of());
    }

    /** The authenticated routes production registers in a process, as {@code METHOD pattern}. */
    private static List<String> authenticatedRoutesOf(String process) {
        return "admin".equals(process) ? List.of("GET /api/v1/auth/sessions/current") : List.of();
    }

    /** The public routes production registers in a process: the login, in the administrative one. */
    private static List<String> publicRoutesOf(String process) {
        return "admin".equals(process) ? List.of("POST /api/v1/auth/sessions") : List.of();
    }

    /** Every route production registers in a process: its public ones plus its authenticated ones. */
    private static List<String> productionRoutesOf(String process) {
        List<String> all = new java.util.ArrayList<>(publicRoutesOf(process));
        all.addAll(authenticatedRoutesOf(process));
        if ("admin".equals(process)) {
            all.add("POST /api/v1/auth/mfa/verifications");
            all.add("POST /api/v1/auth/mfa/enrollments");
        }
        return all;
    }

    /** BI20, the first control: an authenticated entry whose route answers {@code 200} to anyone. */
    @Test
    void anAuthenticatedEntryThatAnswersWithoutACredentialFailsNamingTheRoute() {
        try (HarnessProcess harness = HarnessProcess.start()) {
            AuthenticatedEndpoints listsAnOpenRoute = new AuthenticatedEndpoints(List.of(
                    new AuthenticatedEndpoint(HttpMethod.GET, "/test/open",
                            SessionCheck.TOKEN_ONLY),
                    new AuthenticatedEndpoint(HttpMethod.GET, "/x", SessionCheck.TOKEN_ONLY)));

            List<String> holes = authenticatedRoutesThatAnswerWithoutACredential(harness.port(),
                    listsAnOpenRoute);

            assertThat(holes).containsExactly("GET /test/open is on the authenticated list and "
                    + "answered 200 to an anonymous request");
            assertThat(authenticatedEntriesWithoutARoute(harness.context(),
                    new AuthenticatedEndpoints(List.of(new AuthenticatedEndpoint(HttpMethod.GET,
                            "/ghost", SessionCheck.LIVE_SESSION)))))
                    .containsExactly("GET /ghost is on the authenticated list and no route "
                            + "serves it");
        }
    }

    /**
     * BI20, the second control, and the inversion of the enumerator: a route on neither list that
     * a chain serves to an authenticated actor is named, and one the chain denies with {@code 403}
     * is not. The harness registers many routes that are on neither real list.
     */
    @Test
    void aRouteOnNeitherListThatServesAnAuthenticatedActorFailsNamingTheRoute() {
        try (HarnessProcess harness = HarnessProcess.start()) {
            PublicEndpoints realList = PublicEndpoints.forAdmin(
                    harness.context().getEnvironment());
            AuthenticatedEndpoints realAuthenticated = AuthenticatedEndpoints.forAdmin();

            assertThat(routesOnNeitherList(harness.context(), realList, realAuthenticated))
                    .extracting(Route::pattern).contains("/x", "/test/open", "/test/whoami");
            List<String> served = routesOnNeitherListThatServeAnAuthenticatedActor(
                    harness.context(), harness.port(), realList, realAuthenticated,
                    harness.tokens().access());

            assertThat(served).anyMatch(line -> line.startsWith("GET /test/open answered 200"));
            assertThat(served).anyMatch(line -> line.startsWith("GET /test/whoami answered 200"));
            assertThat(served).noneMatch(line -> line.contains(" /x "));
        }
    }

    /** The restricted list the harness chain declares, as the production list will once it is filled. */
    private static AuthenticatedEndpoints harnessRestricted() {
        return new AuthenticatedEndpoints(List.of(), List.of(
                new SecondFactorEndpoint(HttpMethod.POST, "/test/second-factor/verify",
                        java.util.Set.of(SecondFactorPurpose.VERIFY, SecondFactorPurpose.ENROLL)),
                new SecondFactorEndpoint(HttpMethod.POST, "/test/second-factor/enroll",
                        java.util.Set.of(SecondFactorPurpose.ENROLL))));
    }

    /**
     * WE68, the control: the harness chain accepts a restricted token on two routes, and a list that
     * does not name them is a failure that names both, while the list that does name them is clean.
     * Without the control the enumerator could pass for any list.
     */
    @Test
    void aRouteThatAcceptsARestrictedTokenWithoutBeingOnTheRestrictedListFailsNamingTheRoute() {
        try (HarnessProcess harness = HarnessProcess.start()) {
            String token = harness.tokens().restricted(
                    com.confia.shared.security.token.MfaPurpose.MFA_ENROLL);

            List<String> unlisted = routesThatAcceptARestrictedTokenOutsideTheClass(
                    harness.context(), harness.port(), new AuthenticatedEndpoints(List.of()), token);
            List<String> listed = routesThatAcceptARestrictedTokenOutsideTheClass(
                    harness.context(), harness.port(), harnessRestricted(), token);

            assertThat(unlisted).containsExactlyInAnyOrder(
                    "POST /test/second-factor/verify answered 200 to a restricted token and is not "
                            + "on the restricted list",
                    "POST /test/second-factor/enroll answered 200 to a restricted token and is not "
                            + "on the restricted list");
            assertThat(listed).as("every other route refuses the restricted token").isEmpty();
        }
    }

    /** The restricted entries are held to the same two checks as the authenticated ones. */
    @Test
    void aRestrictedEntryWithoutARouteOrAnsweringAnonymousRequestsFailsNamingTheEntry() {
        try (HarnessProcess harness = HarnessProcess.start()) {
            AuthenticatedEndpoints ghost = new AuthenticatedEndpoints(List.of(), List.of(
                    new SecondFactorEndpoint(HttpMethod.POST, "/ghost",
                            java.util.Set.of(SecondFactorPurpose.ENROLL))));
            AuthenticatedEndpoints open = new AuthenticatedEndpoints(List.of(), List.of(
                    new SecondFactorEndpoint(HttpMethod.GET, "/test/open",
                            java.util.Set.of(SecondFactorPurpose.ENROLL))));

            assertThat(secondFactorEntriesWithoutARoute(harness.context(), ghost)).containsExactly(
                    "POST /ghost is on the restricted list and no route serves it");
            assertThat(secondFactorRoutesThatAnswerWithoutACredential(harness.port(), open))
                    .containsExactly("GET /test/open is on the restricted list and answered 200 to "
                            + "an anonymous request");
            assertThat(secondFactorRoutesThatAnswerWithoutACredential(harness.port(),
                    harnessRestricted())).isEmpty();
            assertThat(routesOnNeitherList(harness.context(),
                    PublicEndpoints.forAdmin(harness.context().getEnvironment()),
                    harnessRestricted())).extracting(Route::pattern)
                    .doesNotContain("/test/second-factor/verify", "/test/second-factor/enroll");
        }
    }

    /**
     * The negative control: the harness chain permits {@code /test/open}, {@code /test/boom} and
     * {@code /test/origin} without listing them in the real allow-list, which is exactly what a
     * route added to a chain and forgotten in {@code PublicEndpoints} looks like. The check must
     * fail, naming each route; {@code /x}, which the chain denies, must not be named.
     */
    @Test
    void aRoutePermittedByAChainAndMissingFromTheAllowListFailsNamingTheRoute() {
        try (HarnessProcess harness = HarnessProcess.start()) {
            PublicEndpoints realList = PublicEndpoints.forAdmin(
                    harness.context().getEnvironment());

            List<String> leaks = leaks(harness.context(), harness.port(), realList);

            // Every route the harness adds lives under /test/ and is permitted by its chain and
            // absent from the real list, so each is a leak; the count is not pinned, so a harness
            // route added by a later task does not need an edit here.
            assertThat(leaks).as("non-vacuous: the harness leaks").hasSizeGreaterThanOrEqualTo(3);
            assertThat(leaks).allMatch(leak -> leak.contains(" /test/"));
            assertThat(leaks).anyMatch(leak -> leak.startsWith("GET /test/open answered 200"));
            assertThat(leaks).anyMatch(leak -> leak.startsWith("GET /test/boom answered 500"));
            assertThat(leaks).anyMatch(leak -> leak.startsWith("GET /test/origin answered 200"));
            assertThat(leaks).noneMatch(leak -> leak.contains("/x"));
            assertThatThrownBy(() -> assertThat(leaks).isEmpty())
                    .hasMessageContaining("/test/open").hasMessageContaining("/test/boom")
                    .hasMessageContaining("/test/origin");
        }
    }

    /** The negative control of the guard above: a functional router in a context is reported. */
    @Test
    void aFunctionalRouterFailsSayingItsRoutesMustBeEnumeratedFirst() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(
                FunctionalRouterConfiguration.class)) {
            assertThat(RegisteredRoutes.of(context)).contains(
                    new Route(RegisteredRoutes.ANY_METHOD, RegisteredRoutes.FUNCTIONAL_ROUTER));
            assertThat(unenumerableRoutes(context)).containsExactly(
                    "(functional router) is registered: functional routes must be enumerated "
                            + "before they are allowed");
        }
        try (AnnotationConfigApplicationContext empty = new AnnotationConfigApplicationContext(
                RouterFunctionMapping.class)) {
            assertThat(unenumerableRoutes(empty)).as("no router, nothing to report").isEmpty();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FunctionalRouterConfiguration {

        @Bean
        RouterFunction<ServerResponse> functionalRoute() {
            return RouterFunctions.route().GET("/functional", request -> ServerResponse.ok()
                    .build()).build();
        }

        @Bean
        RouterFunctionMapping routerFunctionMapping() {
            return new RouterFunctionMapping();
        }
    }

    @Test
    void anAllowListEntryNoRouteServesFailsNamingTheEntry() {
        try (HarnessProcess harness = HarnessProcess.start()) {
            PublicEndpoints withGhost = new PublicEndpoints(List.of(
                    new PublicEndpoint(HttpMethod.GET, "/test/open"),
                    new PublicEndpoint(HttpMethod.GET, "/ghost")));

            assertThat(entriesWithoutARoute(harness.context(), withGhost))
                    .containsExactly("GET /ghost is allow-listed and no route serves it");
        }
    }

    @ParameterizedTest
    @CsvSource({"/v3/api-docs/{group}, /v3/api-docs/sample", "/swagger-ui/**, /swagger-ui/sample",
            "/a/*/b, /a/sample/b", "/plain, /plain"})
    void aPatternBecomesAConcretePath(String pattern, String path) {
        assertThat(sample(pattern)).isEqualTo(path);
    }
}
