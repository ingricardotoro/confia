package com.confia.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.edge.ProblemErrorReportValve;
import com.confia.shared.web.problem.ProblemCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Which code answers each status the container reports (web-edge-foundations design.md, decision
 * 8, and the dated note of task 2.2): {@code 405} has its own code, a server error is an internal
 * error, and every other client error, among them those the catalog has no status for, is
 * {@code validation-failed}. The real answers of the real processes are in
 * {@code ContainerRejectionsTest}.
 */
class ProblemErrorReportValveTest {

    @ParameterizedTest
    @CsvSource({"400, validation-failed", "404, validation-failed", "414, validation-failed",
            "431, validation-failed", "401, authentication-required", "403, forbidden",
            "405, method-not-allowed", "500, internal-error",
            "503, internal-error"})
    void eachStatusTheContainerReportsHasOneCode(int status, String code) {
        assertThat(ProblemErrorReportValve.codeFor(status).code()).isEqualTo(code);
    }

    @ParameterizedTest
    @CsvSource({"401, 401", "403, 403", "405, 405", "414, 400", "503, 500"})
    void theAnswerCarriesTheStatusOfItsCodeAndNotTheOneTheContainerReported(int reported,
            int answered) {
        ProblemCode code = ProblemErrorReportValve.codeFor(reported);

        assertThat(code.status()).isEqualTo(answered);
    }
}
