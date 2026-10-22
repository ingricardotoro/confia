package com.confia.shared.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.harness.HarnessProcess;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * Probe S-5 and scenario "Propiedad desconocida" [WE78] of specs/web-edge (session-endpoints-
 * cookie-and-csrf design.md, decision 9): the JSON body of the administrative chain refuses a
 * property it does not declare with {@code 400 validation-failed}, and neither the name nor the
 * value of that property appears in the body or in the headers of the answer. The value of the
 * default of Jackson 3 under Spring Boot 4.1.1 could not be asserted by reading, so this is the
 * first red of the slice: a body with a field the record does not have must not be answered 200.
 */
class UnknownJsonPropertyTest {

    private static final String ORIGIN = HarnessProcess.ALLOWED_ORIGIN;
    private static final String UNKNOWN_NAME = "campo-extra-zeta";

    private static HttpResponse<String> post(HarnessProcess process, String body) {
        return process.sendWithBody("POST", "/test/single-field", body,
                "Content-Type", "application/json", "Origin", ORIGIN);
    }

    private static String everythingOf(HttpResponse<String> response) {
        return response.body() + " " + response.headers().map();
    }

    @Test
    void aBodyWithAPropertyTheRecordDoesNotDeclareIsRefusedWithValidationFailed() {
        try (HarnessProcess process = HarnessProcess.start()) {
            HttpResponse<String> response = post(process,
                    "{\"a\":\"x\",\"" + UNKNOWN_NAME + "\":\"" + HarnessProcess.SENSITIVE_VALUE + "\"}");

            assertProblem(response, 400, "validation-failed");
            assertThat(everythingOf(response)).as("neither the value nor the name is repeated")
                    .doesNotContain(HarnessProcess.SENSITIVE_VALUE).doesNotContain(UNKNOWN_NAME);
        }
    }

    @Test
    void theSameBodyWithoutTheExtraPropertyIsAccepted() {
        // The control of the probe: the route does accept a well-formed body, so the 400 above is
        // the unknown property and nothing else.
        try (HarnessProcess process = HarnessProcess.start()) {
            HttpResponse<String> response = post(process, "{\"a\":\"x\"}");

            assertThat(response.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void anUnknownPropertyHoldingAnObjectIsStillRefusedWithoutEchoingIt() {
        try (HarnessProcess process = HarnessProcess.start()) {
            HttpResponse<String> response = post(process,
                    "{\"a\":\"x\",\"" + UNKNOWN_NAME + "\":{\"b\":\"" + HarnessProcess.SENSITIVE_VALUE
                            + "\"}}");

            assertProblem(response, 400, "validation-failed");
            assertThat(everythingOf(response)).doesNotContain(HarnessProcess.SENSITIVE_VALUE)
                    .doesNotContain(UNKNOWN_NAME);
        }
    }
}
