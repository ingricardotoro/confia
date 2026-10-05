package com.confia.shared.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertBaseSecurityHeaders;
import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.harness.HarnessProcess;
import java.io.IOException;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Specs/web-edge, "El identificador de petición lo genera el servidor", the last-resort rule of
 * "Toda respuesta de error usa Problem Details", "La IP del cliente se obtiene solo a través de
 * proxies de confianza" and "El agente de usuario se captura acotado" (design.md decisions 8, 11
 * and 12): the id is a UUID made by the server, never the client's, present even when the chain
 * answers before any controller; the client address is the peer's unless the peer is a trusted
 * proxy; the agent is cut to its limit and never rejects a request.
 */
class RequestContextFilterTest {

    private static final String CLIENT_TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String FORWARDED = "X-Forwarded-For";
    private static final List<String> LOOPBACK = List.of("127.0.0.1", "0:0:0:0:0:0:0:1");

    private static HarnessProcess process;
    /** The same process with the loopback addresses, the peer of these tests, as trusted proxies. */
    private static HarnessProcess behindAProxy;

    @BeforeAll
    static void start() {
        process = HarnessProcess.start();
        behindAProxy = HarnessProcess.start("confia.web.trusted-proxies=127.0.0.1,::1");
    }

    @AfterAll
    static void stop() {
        process.close();
        behindAProxy.close();
    }

    private static JsonNode origin(HarnessProcess target, String... headers) {
        HttpResponse<String> response = target.get("/test/origin", headers);
        assertThat(response.statusCode()).isEqualTo(200);
        return HarnessProcess.json(response);
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
    void eachRequestSeesItsOwnIdInTheLogContext() {
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

    @Test
    void theRequestContextHoldsTheServerIdAndTheAddressOfTheConnection() {
        JsonNode origin = origin(process, "X-Request-Id", "cliente-123");

        assertThat(UUID.fromString(origin.get("requestId").asString())).isNotNull();
        assertThat(origin.get("requestIdAttribute").asString())
                .isEqualTo(origin.get("requestId").asString());
        assertThat(origin.get("sourceIp").asString()).isIn(LOOPBACK);
    }

    @Test
    void aForwardedHeaderIsIgnoredWhileNoProxyIsTrusted() {
        JsonNode origin = origin(process, FORWARDED, "198.51.100.7");

        assertThat(origin.get("sourceIp").asString()).isIn(LOOPBACK);
    }

    @Test
    void aForwardedHeaderFromATrustedProxyDecidesTheClientAddress() {
        assertThat(origin(behindAProxy, FORWARDED, "1.1.1.1, 198.51.100.7").get("sourceIp")
                .asString()).isEqualTo("198.51.100.7");
        assertThat(origin(behindAProxy, FORWARDED, "198.51.100.7, basura").get("sourceIp")
                .asString()).isIn(LOOPBACK);
    }

    @Test
    void theUserAgentIsCutToTheLimitAndNoRequestIsRejectedForIt() {
        String atTheLimit = "a".repeat(512);

        assertThat(origin(process, "User-Agent", atTheLimit).get("userAgent").asString())
                .isEqualTo(atTheLimit);
        assertThat(origin(process, "User-Agent", atTheLimit + "b").get("userAgent").asString())
                .isEqualTo(atTheLimit);
        try (HarnessProcess limited = HarnessProcess.start("confia.web.user-agent-max-length=20")) {
            assertThat(origin(limited, "User-Agent", "c".repeat(20)).get("userAgent").asString())
                    .isEqualTo("c".repeat(20));
            assertThat(origin(limited, "User-Agent", "d".repeat(21)).get("userAgent").asString())
                    .isEqualTo("d".repeat(20));
        }
    }

    @Test
    void aRequestWithoutAUserAgentIsProcessedWithANullAgent() throws IOException {
        // The JDK client always sends a User-Agent, so this one goes by a raw socket.
        String answer;
        try (Socket socket = new Socket("localhost", process.port())) {
            socket.getOutputStream().write(("GET /test/origin HTTP/1.1\r\nHost: localhost\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            answer = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(answer).startsWith("HTTP/1.1 200");
        JsonNode origin = HarnessProcess.json(
                answer.substring(answer.indexOf('{'), answer.lastIndexOf('}') + 1));
        assertThat(origin.get("userAgent").isNull()).isTrue();
        assertThat(origin.get("sourceIp").asString()).isIn(LOOPBACK);
    }

    @Test
    void aHundredSimultaneousRequestsEachGetTheirOwnIdAddressAndAgent() throws Exception {
        int requests = 100;
        Set<String> ids = new HashSet<>();
        // Two rounds against the same server: the second one is served by threads the first used,
        // and a value left behind on a reused thread would show as the previous round's agent.
        for (int round = 0; round < 2; round++) {
            CyclicBarrier allReady = new CyclicBarrier(requests);
            List<Future<JsonNode>> calls = new ArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
                for (int i = 0; i < requests; i++) {
                    int n = i;
                    String agent = "agent-" + round + "-" + n;
                    calls.add(pool.submit(() -> {
                        allReady.await(30, TimeUnit.SECONDS);
                        return origin(behindAProxy, FORWARDED, "198.51.100." + (n + 1),
                                "User-Agent", agent);
                    }));
                }
                for (int i = 0; i < requests; i++) {
                    JsonNode origin = calls.get(i).get(60, TimeUnit.SECONDS);
                    assertThat(origin.get("sourceIp").asString())
                            .isEqualTo("198.51.100." + (i + 1));
                    assertThat(origin.get("userAgent").asString())
                            .isEqualTo("agent-" + round + "-" + i);
                    assertThat(origin.get("requestIdAttribute").asString())
                            .isEqualTo(origin.get("requestId").asString());
                    ids.add(origin.get("requestId").asString());
                }
            }
        }
        assertThat(ids).as("every one of the 200 requests had its own id").hasSize(2 * requests);
    }
}
