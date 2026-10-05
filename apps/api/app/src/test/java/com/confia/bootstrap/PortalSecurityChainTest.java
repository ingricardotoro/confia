package com.confia.bootstrap;

import static com.confia.shared.web.harness.ProblemAssertions.assertBaseSecurityHeaders;
import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.harness.HarnessProcess;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Specs/web-edge, "La cadena del portal deniega toda ruta" (design.md decision 5): the real
 * portal, started through the production launcher, answers {@code 401} with Problem Details to
 * every route with every method. Only when springdoc is on does the documentation open, and
 * nothing else does.
 *
 * <p>This class lives in {@code com.confia.bootstrap} next to the other tests of the real
 * processes because the launcher and {@link OpenApiProcess} are package-private (ADR-0024); the
 * task named {@code shared.web}, a deviation recorded in {@code apply-progress.md}.
 */
class PortalSecurityChainTest {

    private static final List<String> METHODS =
            List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    private static OpenApiProcess portal;

    @BeforeAll
    static void start() {
        portal = OpenApiProcess.start("portal");
    }

    @AfterAll
    static void stop() {
        portal.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/does-not-exist", "/api/v1/students"})
    void everyRouteIsDeniedForEveryMethodWithTheUniformCode(String path) {
        for (String method : METHODS) {
            HttpResponse<String> response = portal.send(method, path);

            assertThat(response.statusCode()).as("%s %s", method, path).isEqualTo(401);
            assertThat(response.headers().firstValue("Content-Type"))
                    .as("%s %s", method, path)
                    .hasValueSatisfying(type -> assertThat(type)
                            .startsWith("application/problem+json"));
            assertBaseSecurityHeaders(response);
            if (!"HEAD".equals(method)) {
                assertProblem(response, 401, "authentication-required");
            }
        }
    }

    @Test
    void theDocumentationOfAProductionPortalIsDeniedLikeAnythingElse() {
        assertProblem(portal.get(OpenApiProcess.API_DOCS_PATH), 401, "authentication-required");
        assertProblem(portal.get(OpenApiProcess.SWAGGER_UI_PATH), 401, "authentication-required");
    }

    @Test
    void withSpringdocOnOnlyTheDocumentationOpensAndEverythingElseStaysDenied() {
        try (OpenApiProcess local = OpenApiProcess.start("portal", "local")) {
            HttpResponse<String> document = local.get(OpenApiProcess.API_DOCS_PATH);

            assertThat(document.statusCode()).isEqualTo(200);
            assertThat(document.body()).contains("\"openapi\":\"3.1");
            assertProblem(local.get("/"), 401, "authentication-required");
            assertProblem(local.get("/api/v1/students"), 401, "authentication-required");
            assertProblem(local.send("POST", OpenApiProcess.API_DOCS_PATH), 401,
                    "authentication-required");
        }
    }

    @Test
    void aRouteAddedToThePortalContextIsStillDeniedAndItsControllerNeverRuns() {
        try (HarnessProcess harness = HarnessProcess.startAsPortal()) {
            assertThat(harness.get("/test/open").statusCode())
                    .as("non-vacuous: the harness really serves a route the allow-list admits")
                    .isEqualTo(200);

            assertProblem(harness.get("/x"), 401, "authentication-required");
            assertThat(harness.calls().protectedInvocations()).isZero();
        }
    }
}
