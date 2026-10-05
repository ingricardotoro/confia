package com.confia.shared.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertBaseSecurityHeaders;
import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.harness.HarnessProcess;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Specs/web-edge, "El identificador de petición lo genera el servidor" and the last-resort rule of
 * "Toda respuesta de error usa Problem Details" (design.md decisions 8 and 11): the id is a UUID
 * made by the server, never the client's, present even when the chain answers before any
 * controller, and the trace id of an error is the id the request carried.
 */
class RequestContextFilterTest {

    private static final String CLIENT_TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

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
    void theServerIgnoresTheIdentifiersTheClientSends() {
        HttpResponse<String> response = process.get("/x",
                "X-Request-Id", "cliente-123",
                "traceparent", "00-" + CLIENT_TRACE_ID + "-b7ad6b7169203331-01");

        JsonNode body = assertProblem(response, 401, "authentication-required");

        String traceId = body.get("traceId").asString();
        assertThat(UUID.fromString(traceId)).isNotNull();
        assertThat(response.body()).doesNotContain("cliente-123").doesNotContain(CLIENT_TRACE_ID);
        assertThat(response.headers().firstValue("X-Request-Id")).isEmpty();
    }

    @Test
    void aResponseOfTheChainBeforeAnyControllerStillCarriesTheServerId() {
        JsonNode body = assertProblem(process.get("/x"), 401, "authentication-required");

        assertThat(body.get("traceId").asString()).hasSize(36);
        assertThat(process.calls().protectedInvocations()).isZero();
    }

    @Test
    void everyRequestGetsItsOwnId() {
        String first = assertProblem(process.get("/x"), 401, "authentication-required")
                .get("traceId").asString();
        String second = assertProblem(process.get("/x"), 401, "authentication-required")
                .get("traceId").asString();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void theTraceIdOfAnErrorIsTheIdTheControllerSawInTheRequestAndInTheLogContext() {
        HttpResponse<String> response = process.get("/test/boom");

        JsonNode body = assertProblem(response, 500, "internal-error");

        String traceId = body.get("traceId").asString();
        assertThat(process.calls().lastRequestIdAttribute()).isEqualTo(traceId);
        assertThat(process.calls().lastRequestIdInMdc()).isEqualTo(traceId);
    }

    @Test
    void theLogContextOfOneRequestNeverCarriesTheIdOfThePreviousOne() {
        process.get("/test/boom");
        String first = process.calls().lastRequestIdInMdc();
        process.get("/test/boom");
        String second = process.calls().lastRequestIdInMdc();

        assertThat(first).isNotNull().isNotEqualTo(second);
        assertThat(second).isNotNull();
    }

    @Test
    void theLastResortAnswersWithoutAnythingInternalAndKeepsTheSecurityHeaders() {
        HttpResponse<String> response = process.get("/test/boom");

        assertProblem(response, 500, "internal-error");
        assertThat(response.body()).doesNotContain("password").doesNotContain("jdbc")
                .doesNotContain("IllegalStateException").doesNotContain("RequestContextFilter");
        assertBaseSecurityHeaders(response);
    }
}
