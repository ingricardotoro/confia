package com.confia.shared.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.harness.HarnessProcess;
import com.confia.shared.web.harness.HarnessTokens;
import java.io.IOException;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Specs/organization, ADR-0009 end to end: the real chain, the real bearer filter and the real
 * adapter of {@code CurrentInstitutionProvider} behind the database-free harness, over HTTP. The
 * institution a route resolves is the one of the verified token, and no header, parameter, cookie
 * or body changes it or turns it into an error.
 */
class CurrentInstitutionFromTokenTest {

    private static final UUID PROCESS_INSTITUTION =
            UUID.fromString("5a6a5e02-1c2e-4e3a-9d3f-6b2b3a1e9f10");
    private static final UUID OTHER = UUID.fromString("e7c1b2a4-6d3f-4a8b-9c5e-1f2a3b4c5d6e");
    private static final String ROUTE = "/test/institution";
    private static final int REQUESTS = 50;

    private static HarnessProcess process;

    @BeforeAll
    static void start() {
        process = HarnessProcess.start(
                "confia.identity.login-institution-id=" + PROCESS_INSTITUTION);
    }

    @AfterAll
    static void stop() {
        process.close();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String resolved(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        return HarnessProcess.json(response).get("institutionId").asString();
    }

    // --- OR01 and OR02 ---

    @Test
    void theInstitutionIsTheOneOfTheToken() {
        HttpResponse<String> response = process.get(ROUTE,
                "Authorization", bearer(process.tokens().access()));

        assertThat(resolved(response)).isEqualTo(HarnessTokens.INSTITUTION.toString());
    }

    @Test
    void theInstitutionOfTheProcessConfigurationIsNotUsed() {
        HttpResponse<String> other = process.get(ROUTE, "Authorization",
                bearer(process.tokens().accessForInstitution(OTHER)));
        HttpResponse<String> own = process.get(ROUTE, "Authorization",
                bearer(process.tokens().accessForInstitution(PROCESS_INSTITUTION)));

        assertThat(resolved(other)).isEqualTo(OTHER.toString())
                .isNotEqualTo(PROCESS_INSTITUTION.toString());
        assertThat(resolved(own)).isEqualTo(PROCESS_INSTITUTION.toString());
    }

    // --- OR03 ---

    @Test
    void aPublicRouteThatAsksForTheInstitutionWithoutCredentialIsAnInternalError() {
        HttpResponse<String> response = process.get("/test/institution-open");

        assertProblem(response, 500, "internal-error");
        assertThat(response.body()).doesNotContain("IllegalStateException")
                .doesNotContain("TokenCurrentInstitutionProvider")
                .doesNotContain(HarnessTokens.INSTITUTION.toString());
    }

    // --- OR04 ---

    @Test
    void fiftyConcurrentRequestsOfFiftyInstitutionsEachGetTheirOwn() throws Exception {
        CyclicBarrier together = new CyclicBarrier(REQUESTS);
        try (ExecutorService pool = Executors.newFixedThreadPool(REQUESTS)) {
            List<UUID> institutions = new ArrayList<>();
            List<Future<String>> answers = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                UUID institution = UUID.randomUUID();
                institutions.add(institution);
                String token = process.tokens().accessForInstitution(institution);
                answers.add(pool.submit(() -> {
                    together.await();
                    return resolved(process.get(ROUTE, "Authorization", bearer(token)));
                }));
            }
            assertThat(institutions).doesNotHaveDuplicates().hasSize(REQUESTS);
            for (int i = 0; i < REQUESTS; i++) {
                assertThat(answers.get(i).get()).isEqualTo(institutions.get(i).toString());
            }
        }
    }

    // --- OR06: headers ---

    @Test
    void theInstitutionHeadersAreIgnored() {
        HttpResponse<String> response = process.get(ROUTE,
                "Authorization", bearer(process.tokens().access()),
                "X-Institution-Id", OTHER.toString(),
                "Institution-Id", OTHER.toString(),
                "X-Tenant-Id", OTHER.toString(),
                "X-Forwarded-Host", OTHER.toString());

        assertThat(resolved(response)).isEqualTo(HarnessTokens.INSTITUTION.toString());
        assertThat(response.body()).doesNotContain(OTHER.toString());
    }

    @Test
    void aHostNamingAnotherInstitutionIsIgnored() throws IOException {
        String raw = rawGet(ROUTE, OTHER.toString(), process.tokens().access());

        assertThat(raw).startsWith("HTTP/1.1 200");
        assertThat(raw).contains(HarnessTokens.INSTITUTION.toString())
                .doesNotContain(OTHER.toString());
    }

    // --- OR07: query and cookie ---

    @Test
    void theQueryParametersAndTheCookieOfAnInstitutionAreIgnored() {
        HttpResponse<String> response = process.get(
                ROUTE + "?institutionId=" + OTHER + "&tenant=" + OTHER,
                "Authorization", bearer(process.tokens().access()),
                "Cookie", "institution=" + OTHER + "; tenant=" + OTHER);

        assertThat(resolved(response)).isEqualTo(HarnessTokens.INSTITUTION.toString());
        assertThat(response.body()).doesNotContain(OTHER.toString());
    }

    // --- OR08: body ---

    @Test
    void anInstitutionFieldInTheBodyIsIgnored() {
        HttpResponse<String> response = process.sendWithBody("POST", ROUTE,
                "{\"institutionId\":\"" + OTHER + "\",\"tenant\":\"" + OTHER + "\"}",
                "Authorization", bearer(process.tokens().access()),
                "Content-Type", "application/json");

        assertThat(resolved(response)).isEqualTo(HarnessTokens.INSTITUTION.toString());
        assertThat(response.body()).doesNotContain(OTHER.toString());
    }

    // --- OR09: malformed values ---

    @ParameterizedTest
    @ValueSource(strings = {"no-es-un-uuid", "", "   ", "00000000-0000-0000-0000-000000000000",
            "d290f1ee-6c54-4b01-90e6-d701748f085"})
    void aMalformedInstitutionHeaderNeitherChangesTheResolutionNorFails(String value) {
        HttpResponse<String> response = process.get(ROUTE + "?tenant=%00&institutionId=%27%20OR%201",
                "Authorization", bearer(process.tokens().access()),
                "X-Institution-Id", value);

        assertThat(resolved(response)).isEqualTo(HarnessTokens.INSTITUTION.toString());
    }

    @Test
    void aMalformedBodyInstitutionNeitherChangesTheResolutionNorFails() {
        HttpResponse<String> response = process.sendWithBody("POST", ROUTE,
                "{\"institutionId\":\"no-es-un-uuid\",\"tenant\":null}",
                "Authorization", bearer(process.tokens().access()),
                "Content-Type", "application/json");

        assertThat(resolved(response)).isEqualTo(HarnessTokens.INSTITUTION.toString());
    }

    /** A request with the given {@code Host}, which the JDK client refuses to set. */
    private static String rawGet(String path, String host, String token) throws IOException {
        try (Socket socket = new Socket("localhost", process.port())) {
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: " + host
                    + "\r\nAuthorization: Bearer " + token + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
