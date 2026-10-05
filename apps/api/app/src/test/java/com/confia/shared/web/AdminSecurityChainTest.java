package com.confia.shared.web;

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
 * Specs/web-edge, the administrative chain (design.md decisions 5 and 6): deny by default with a
 * uniform {@code 401} or {@code 403} in Problem Details. The chain is the real one, behind the
 * database-free harness, and a request that must be denied never reaches a controller. The
 * headers, the absence of a session and the fixed language through this chain are proved in the
 * next part of the change, with the request id filter.
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
    @ValueSource(strings = {"/x/", "/X", "/x;a=b", "/x/.", "/y/../x"})
    void aVariantOfAProtectedPathNeverReachesItsController(String path) {
        HttpResponse<String> response = process.get(path);

        assertThat(response.statusCode()).as("%s must not be a 2xx", path).isIn(400, 401);
        assertProblem(response, response.statusCode(),
                response.statusCode() == 400 ? "validation-failed" : "authentication-required");
        assertThat(process.calls().protectedInvocations()).isZero();
    }

    @Test
    void everyDenialForAMissingCredentialIsTheSameWhateverTheRouteOrTheHeader() {
        JsonNode reference = assertProblem(process.get("/a"), 401, "authentication-required");

        for (HttpResponse<String> response : java.util.List.of(
                process.get("/b"),
                process.get("/deep/er/route"),
                process.get("/c", "Authorization", "Bearer not.a.valid.token"),
                process.get("/c", "Authorization", "Basic !!!not-base64!!!"))) {
            JsonNode body = assertProblem(response, 401, "authentication-required");
            for (String field : new String[] {"type", "title", "detail"}) {
                assertThat(body.get(field)).as(field).isEqualTo(reference.get(field));
            }
        }
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
