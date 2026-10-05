package com.confia.bootstrap;

import static com.confia.shared.web.harness.ProblemAssertions.assertBaseSecurityHeaders;
import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.harness.HarnessProcess;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;

/**
 * Specs/web-edge, "Problem Details y cabeceras base en todo error" for what the container itself
 * rejects (web-edge-foundations design.md decisions 7 and 8; the gap that the notes of 2026-10-04
 * assign to task 2.2). Tomcat refuses an encoded separator, an encoded NUL and {@code TRACE}
 * before any filter runs, so neither the security chain nor the headers filter ever sees them; the
 * container's own error report is what answers. These tests start the real administrative and
 * portal processes with their production configuration and require, for each of the three
 * requests, Problem Details, the five base headers, and neither a {@code Server} nor an {@code
 * X-Powered-By} header, which the assertion states instead of assuming.
 */
class ContainerRejectionsTest {

    private static OpenApiProcess admin;
    private static OpenApiProcess portal;

    @BeforeAll
    static void start() {
        admin = OpenApiProcess.start("admin");
        portal = OpenApiProcess.start("portal");
    }

    @AfterAll
    static void stop() {
        admin.close();
        portal.close();
    }

    private static OpenApiProcess processNamed(String name) {
        return "admin".equals(name) ? admin : portal;
    }

    @ParameterizedTest
    @CsvSource({
            "admin, GET, /x%2f, 400, validation-failed",
            "admin, GET, /x%00, 400, validation-failed",
            "admin, TRACE, /x, 405, method-not-allowed",
            "portal, GET, /x%2f, 400, validation-failed",
            "portal, GET, /x%00, 400, validation-failed",
            "portal, TRACE, /x, 405, method-not-allowed"})
    void whatTheContainerRefusesAnswersWithProblemDetailsAndTheBaseHeaders(String process,
            String method, String path, int status, String code) {
        HttpResponse<String> response = processNamed(process).send(method, path);

        assertProblem(response, status, code);
        assertBaseSecurityHeaders(response);
        assertThat(response.headers().firstValue("Content-Type")).as("%s %s", method, path)
                .hasValue("application/problem+json");
    }

    @ParameterizedTest
    @CsvSource({"GET, /x%2f, x%2f", "GET, /x%00, %00", "TRACE, /x, TRACE"})
    void theAnswerRepeatsNeitherTheRejectedPathNorAnythingOfTheContainer(String method, String path,
            String rejected) {
        HttpResponse<String> response = admin.send(method, path);

        assertThat(response.statusCode()).as("non-vacuous: the container did reject it")
                .isIn(400, 405);
        JsonNode body = HarnessProcess.json(response);
        assertThat(body.get("instance").asString()).as("instance").isEqualTo("/");
        assertThat(response.body()).as("the body of %s %s", method, path)
                .doesNotContain(rejected)
                .doesNotContain(rejected.toUpperCase())
                .doesNotContainIgnoringCase("tomcat")
                .doesNotContainIgnoringCase("<html")
                .doesNotContainIgnoringCase("exception");
    }

    @Test
    void aRefusedTraceKeepsTheAllowHeaderTheContainerSet() {
        HttpResponse<String> response = portal.send("TRACE", "/x");

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Allow")).as("the container's Allow header")
                .isPresent();
    }

    @Test
    void aRequestTheChainDeniesIsStillAnsweredByTheChainAndNotByTheContainerReport() {
        HttpResponse<String> response = admin.get("/x");

        assertProblem(response, 401, "authentication-required");
        assertThat(HarnessProcess.json(response).get("instance")
                .asString()).as("the chain still echoes the real path").isEqualTo("/x");
    }

    /**
     * Design decision (dated note of task 2.2): the status of a Problem Details body is the
     * status of the response (RFC 9457), and the status of a code is fixed by the catalog. A
     * container refusal the catalog has no status for is therefore answered with the catalog's
     * nearest code, and the body and the response agree on that one status. An over-long request
     * line is refused by Tomcat before any filter and must not be answered with its HTML page.
     */
    @Test
    void aRefusalWithoutACatalogStatusStillAnswersWithOneStatusInTheBodyAndTheResponse() {
        HttpResponse<String> response = admin.get("/" + "a".repeat(70_000));

        assertThat(response.statusCode()).as("non-vacuous: the container refused it")
                .isGreaterThanOrEqualTo(400);
        JsonNode body = HarnessProcess.json(response);
        assertThat(body.get("status").asInt()).isEqualTo(response.statusCode());
        assertProblem(response, response.statusCode(),
                response.statusCode() >= 500 ? "internal-error" : "validation-failed");
        assertBaseSecurityHeaders(response);
    }
}
