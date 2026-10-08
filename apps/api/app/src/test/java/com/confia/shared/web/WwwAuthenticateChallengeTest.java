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

/**
 * Specs/web-edge, "Todo 401 lleva WWW-Authenticate" (session-tokens-and-web-layer design.md,
 * decision 5; RFC 9110): every {@code 401} of the real chain, whichever component writes it, carries
 * the challenge of its code, and no other status carries one.
 */
class WwwAuthenticateChallengeTest {

    private static HarnessProcess process;

    @BeforeAll
    static void start() {
        process = HarnessProcess.start();
    }

    @AfterAll
    static void stop() {
        process.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/x", "/does/not/exist", "/v3/api-docs"})
    void everyAnonymousDenialCarriesTheBearerChallenge(String path) {
        HttpResponse<String> response = process.get(path);

        assertProblem(response, 401, "authentication-required");
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer");
    }

    @Test
    void theTranslatorsOwn401CarriesTheChallengeToo() {
        HttpResponse<String> response = process.get("/test/status?code=401");

        assertProblem(response, 401, "authentication-required");
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer");
    }

    @Test
    void aForbiddenAnswerCarriesNoChallenge() {
        HttpResponse<String> response = process.get("/x", HarnessProcess.PRINCIPAL_HEADER, "p");

        assertProblem(response, 403, "forbidden");
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    @Test
    void aSuccessfulAnswerCarriesNoChallenge() {
        HttpResponse<String> response = process.get("/test/open");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
    }
}
