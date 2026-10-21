package com.confia.shared.web.edge;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.AdminProcessLauncher;
import com.confia.shared.web.authentication.AccessTokenAuthenticationFilter;
import com.confia.shared.web.csrf.CsrfOriginFilter;
import com.confia.shared.web.harness.HarnessProcess;
import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Specs/web-edge, scenarios WE57 and WE58 (session-endpoints-cookie-and-csrf design.md, decision
 * 2): the CSRF and {@code Origin} filter stands before the token filter, so a request it refuses
 * verifies no token, reads no session and spends no quota of the limiter. The order is read from
 * the chain of the real administrative process and of the harness, and its consequences are
 * observed on the harness through the session-validity double, which the token filter calls only
 * after it has verified a token, and through the real limiter of the {@code admin-login} policy.
 */
class AdminFilterOrderTest {

    @Test
    void inTheRealAdministrativeProcessTheCsrfFilterStandsBeforeTheTokenFilter() {
        ConfigurableApplicationContext context = AdminProcessLauncher.launchWithoutDatabase();
        try {
            List<Filter> filters = context.getBean(SecurityFilterChain.class).getFilters();

            assertRelativeOrder(filters);
        } finally {
            context.close();
        }
    }

    @Test
    void inTheHarnessChainTheCsrfFilterStandsBeforeTheTokenFilter() {
        try (HarnessProcess process = HarnessProcess.start()) {
            assertRelativeOrder(process.securityFilters());
        }
    }

    @Test
    void aRequestRefusedForItsOriginVerifiesNoTokenAndReadsNoSession() {
        try (HarnessProcess process = HarnessProcess.start()) {
            String bearer = "Bearer " + process.tokens().access();
            int validity = process.sessionValidity().invocations();
            int served = process.calls().authenticatedInvocations();

            assertProblem(process.send("POST", "/test/live", "Authorization", bearer, "Origin",
                    "https://evil.example"), 403, "csrf-invalid");
            assertProblem(process.send("POST", "/test/live", "Authorization", bearer), 403,
                    "csrf-invalid");

            assertThat(process.sessionValidity().invocations())
                    .as("the token was never verified, so the session was never read")
                    .isEqualTo(validity);
            assertThat(process.calls().authenticatedInvocations()).isEqualTo(served);

            // Non-vacuous: with an allowed origin the same request does verify and does read.
            assertThat(process.send("POST", "/test/live", "Authorization", bearer, "Origin",
                    HarnessProcess.ALLOWED_ORIGIN).statusCode()).isEqualTo(200);
            assertThat(process.sessionValidity().invocations()).isEqualTo(validity + 1);
        }
    }

    @Test
    void aRequestRefusedForItsOriginSpendsNoQuotaOfTheLimiter() {
        try (HarnessProcess process = HarnessProcess.start()) {
            for (int i = 0; i < 25; i++) {
                assertProblem(process.send("POST", "/test/limited", "Origin", "https://evil.example"),
                        403, "csrf-invalid");
            }

            assertThat(process.calls().limitedInvocations()).isZero();
            for (int i = 0; i < 9; i++) {
                assertThat(process.send("POST", "/test/limited", "Origin",
                        HarnessProcess.ALLOWED_ORIGIN).statusCode())
                        .as("request %d of the allowed origin, after 25 refusals", i + 1)
                        .isEqualTo(200);
            }
            assertThat(process.calls().limitedInvocations()).isEqualTo(9);
        }
    }

    private static void assertRelativeOrder(List<Filter> filters) {
        int csrf = indexOf(filters, CsrfOriginFilter.class);
        int token = indexOf(filters, AccessTokenAuthenticationFilter.class);

        assertThat(csrf).as("the chain holds the CSRF filter").isNotNegative();
        assertThat(token).as("the chain holds the token filter").isNotNegative();
        assertThat(csrf).as("CSRF and Origin before the token filter").isLessThan(token);
    }

    private static int indexOf(List<Filter> filters, Class<? extends Filter> type) {
        for (int i = 0; i < filters.size(); i++) {
            if (type.isInstance(filters.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
