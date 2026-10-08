package com.confia.shared.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertBaseSecurityHeaders;
import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.harness.HarnessProcess;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Specs/web-edge, the administrative chain (design.md decisions 5 to 8): deny by default with a
 * uniform {@code 401} or {@code 403}, no session, the base security headers on every response, and
 * Problem Details with the catalog's fixed language. The chain is the real one, behind the
 * database-free harness, and a request that must be denied never reaches a controller.
 */
class AdminSecurityChainTest {

    private static HarnessProcess process;

    @BeforeAll
    static void start() {
        process = HarnessProcess.start();
    }

    @AfterAll
    static void stop() {
        process.close();
    }

    @Test
    void aRouteNoControllerRegistersIsDeniedWithTheUniformCode() {
        assertProblem(process.get("/does/not/exist"), 401, "authentication-required");
    }

    @Test
    void aRegisteredRouteOutsideTheAllowListIsDeniedAndItsControllerNeverRuns() {
        assertProblem(process.get("/x"), 401, "authentication-required");
        assertThat(process.calls().protectedInvocations()).isZero();
    }

    @Test
    void aDeniedRegisteredRouteAndAnUnregisteredOneAnswerIdentically() {
        HttpResponse<String> registered = process.get("/x");
        HttpResponse<String> unregistered = process.get("/nothing-registers-this");
        JsonNode first = assertProblem(registered, 401, "authentication-required");
        JsonNode second = assertProblem(unregistered, 401, "authentication-required");

        for (String field : new String[] {"type", "title", "status", "detail"}) {
            assertThat(first.get(field)).as(field).isEqualTo(second.get(field));
        }
        assertThat(first.get("instance")).as("only the instance may differ")
                .isNotEqualTo(second.get("instance"));
        assertThat(registered.headers().map()).containsKeys("X-Content-Type-Options",
                "X-Frame-Options", "Referrer-Policy", "Permissions-Policy", "Cache-Control");
        assertThat(unregistered.headers().map()).containsKeys("X-Content-Type-Options",
                "X-Frame-Options", "Referrer-Policy", "Permissions-Policy", "Cache-Control");
    }

    @Test
    void anAuthenticatedPrincipalOutsideTheAllowListGets403AndNotAnotherAnswer() {
        assertProblem(process.get("/x", HarnessProcess.PRINCIPAL_HEADER, "someone"), 403,
                "forbidden");
        assertProblem(process.get("/x"), 401, "authentication-required");
        assertThat(process.calls().protectedInvocations()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH"})
    void aGetOnlyPublicRouteDeniesEveryOtherMethod(String method) {
        int before = process.calls().openInvocations();
        assertThat(process.get("/test/open").statusCode())
                .as("non-vacuous: the route is public for GET").isEqualTo(200);
        assertThat(process.calls().openInvocations()).isEqualTo(before + 1);

        assertProblem(process.send(method, "/test/open"), 401, "authentication-required");
        assertThat(process.calls().openInvocations())
                .as("the denied %s never reached the controller", method).isEqualTo(before + 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD", "OPTIONS"})
    void theMethodsNobodyListedAreDeniedOnAProtectedAndOnAGetOnlyPublicRoute(String method) {
        for (String path : new String[] {"/x", "/test/open"}) {
            HttpResponse<String> response = process.send(method, path);

            assertThat(response.statusCode()).as("%s %s", method, path).isEqualTo(401);
            assertThat(response.headers().firstValue("Content-Type")).as("%s %s", method, path)
                    .hasValueSatisfying(type -> assertThat(type)
                            .startsWith("application/problem+json"));
        }
        assertThat(process.calls().protectedInvocations()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/x/", "/X", "/x;a=b", "/x/.", "/y/../x", "//x", "/x%2e", "/%78"})
    void aVariantOfAProtectedPathNeverReachesItsController(String path) {
        HttpResponse<String> response = process.get(path);

        assertThat(response.statusCode()).as("%s must not be a 2xx", path).isIn(400, 401);
        assertProblem(response, response.statusCode(),
                response.statusCode() == 400 ? "validation-failed" : "authentication-required");
        assertThat(process.calls().protectedInvocations()).isZero();
    }

    /**
     * Tomcat refuses these, and {@code TRACE}, before any filter runs, so the answer is its own page
     * and not Problem Details, and carries none of the base headers. They are safe (no controller
     * runs, no {@code 2xx}) but not yet uniform; {@code apply-progress.md} records the gap.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/x%2f", "/x%00"})
    void anEncodedSeparatorOrNulIsRefusedByTheContainerAndNeverReachesAController(String path) {
        assertThat(process.get(path).statusCode()).as("%s", path).isEqualTo(400);
        assertThat(process.calls().protectedInvocations()).isZero();
    }

    @Test
    void traceIsRefusedByTheContainerAndNeverReachesAController() {
        assertThat(process.send("TRACE", "/x").statusCode()).isEqualTo(405);
        assertThat(process.send("TRACE", "/test/open").statusCode()).isEqualTo(405);
        assertThat(process.calls().protectedInvocations()).isZero();
    }

    @Test
    void noResponseCreatesASessionOrSetsACookie() {
        int before = process.sessionsCreated();

        HttpResponse<String> open = process.get("/test/open");
        HttpResponse<String> denied = process.get("/x");
        HttpResponse<String> forbidden = process.get("/x", HarnessProcess.PRINCIPAL_HEADER, "p");

        assertThat(open.statusCode()).isEqualTo(200);
        assertThat(denied.statusCode()).isEqualTo(401);
        assertThat(forbidden.statusCode()).isEqualTo(403);
        for (HttpResponse<String> response : java.util.List.of(open, denied, forbidden)) {
            assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        }
        assertThat(process.sessionsCreated()).isEqualTo(before);
    }

    @Test
    void anErrorResponseCarriesTheBaseSecurityHeaders() {
        assertBaseSecurityHeaders(process.get("/x"));
        assertBaseSecurityHeaders(process.get("/x", HarnessProcess.PRINCIPAL_HEADER, "p"));
    }

    @Test
    void aSuccessResponseCarriesTheSameBaseSecurityHeaders() {
        HttpResponse<String> open = process.get("/test/open");

        assertThat(open.statusCode()).isEqualTo(200);
        assertBaseSecurityHeaders(open);
    }

    @Test
    void theQueryStringNeverAppearsInTheInstance() {
        HttpResponse<String> response = process.get("/x?token=secreto");

        JsonNode body = assertProblem(response, 401, "authentication-required");
        assertThat(body.get("instance").asString()).isEqualTo("/x");
        assertThat(response.body()).doesNotContain("token").doesNotContain("secreto");
    }

    @Test
    void everyDenialForAMissingCredentialIsTheSameWhateverTheRouteOrTheHeader() {
        JsonNode reference = assertProblem(process.get("/a"), 401, "authentication-required");

        for (HttpResponse<String> response : java.util.List.of(
                process.get("/b"),
                process.get("/deep/er/route"),
                process.get("/c", "Authorization", "Basic !!!not-base64!!!"))) {
            JsonNode body = assertProblem(response, 401, "authentication-required");
            for (String field : new String[] {"type", "title", "detail"}) {
                assertThat(body.get(field)).as(field).isEqualTo(reference.get(field));
            }
        }
    }

    @Test
    void aBrokenBearerCredentialIsNotTheUniformDenialAnymoreAndSaysSo() {
        HttpResponse<String> response = process.get("/c", "Authorization",
                "Bearer not.a.valid.token");

        assertProblem(response, 401, "token-invalid");
    }

    // --- The authenticated list: a closed list, parallel to the public one ---

    @Test
    void anAuthenticatedRouteWithoutACredentialIsDeniedBeforeItsControllerRuns() {
        int before = process.calls().authenticatedInvocations();

        assertProblem(process.get("/test/whoami"), 401, "authentication-required");
        assertProblem(process.send("POST", "/test/whoami"), 401, "authentication-required");

        assertThat(process.calls().authenticatedInvocations()).isEqualTo(before);
    }

    @Test
    void aRegisteredRouteNobodyAddedToEitherListIsDeniedEvenToAValidToken() {
        int before = process.calls().protectedInvocations();
        String token = process.tokens().access();

        assertProblem(process.get("/x", "Authorization", "Bearer " + token), 403, "forbidden");

        assertThat(process.calls().protectedInvocations()).isEqualTo(before);
    }

    @Test
    void theLanguageIsFixedWhateverAcceptLanguageSays() {
        String detail = ProblemCatalogCoverageTest.loadCatalog()
                .getProperty("problem.authentication-required.detail");

        JsonNode plain = assertProblem(process.get("/x"), 401, "authentication-required");
        JsonNode english = assertProblem(process.get("/x", "Accept-Language", "en-US"), 401,
                "authentication-required");

        assertThat(detail).isNotBlank();
        assertThat(plain.get("detail").asString()).isEqualTo(detail);
        assertThat(english.get("detail").asString()).isEqualTo(detail);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui.html",
            "/swagger-ui/index.html"})
    void withoutSpringdocTheDocumentationIsNeitherPublicNorServed(String path) {
        HttpResponse<String> response = process.get(path);

        assertProblem(response, 401, "authentication-required");
        assertThat(response.body()).as("a problem, not the document and not the interface")
                .doesNotContain("\"openapi\"").doesNotContain("swagger-ui-bundle");
    }
}
