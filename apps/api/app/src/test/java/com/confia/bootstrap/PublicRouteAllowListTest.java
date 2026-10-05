package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.bootstrap.RegisteredRoutes.Route;
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
 * production configuration (no Spring profile) registers no route at all in either process.
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
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
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
                assertThat(routes).as("production registers no route in %s", process).isEmpty();
                assertThat(allowed.endpoints()).isEmpty();
            } else {
                assertThat(routes).as("non-vacuous: springdoc registers its routes in local")
                        .isNotEmpty();
                assertThat(allowed.endpoints()).as("non-vacuous: and lists them").isNotEmpty();
            }
            assertThat(leaks(context, running.port(), allowed)).isEmpty();
            assertThat(entriesWithoutARoute(context, allowed)).isEmpty();
        }
    }

    /**
     * The negative control: the harness chain permits {@code /test/open} and {@code /test/boom}
     * without listing them in the real allow-list, which is exactly what a route added to a chain
     * and forgotten in {@code PublicEndpoints} looks like. The check must fail, naming each
     * route; {@code /x}, which the chain denies, must not be named.
     */
    @Test
    void aRoutePermittedByAChainAndMissingFromTheAllowListFailsNamingTheRoute() {
        try (HarnessProcess harness = HarnessProcess.start()) {
            PublicEndpoints realList = PublicEndpoints.forAdmin(
                    harness.context().getEnvironment());

            List<String> leaks = leaks(harness.context(), harness.port(), realList);

            assertThat(leaks).hasSize(2);
            assertThat(leaks).anyMatch(leak -> leak.startsWith("GET /test/open answered 200"));
            assertThat(leaks).anyMatch(leak -> leak.startsWith("GET /test/boom answered 500"));
            assertThat(leaks).noneMatch(leak -> leak.contains("/x"));
            assertThatThrownBy(() -> assertThat(leaks).isEmpty())
                    .hasMessageContaining("/test/open").hasMessageContaining("/test/boom");
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
