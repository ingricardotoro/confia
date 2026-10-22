package com.confia.shared.web.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.harness.HarnessProcess;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/**
 * Specs/web-edge, scenario "Los contadores de las políticas son independientes" [WE71] (session-
 * endpoints-cookie-and-csrf design.md, decision 7): each of the three policies has its own limiter,
 * so a client whose quota of one policy is spent is still admitted by the other two. The harness
 * routes carry {@code admin-login}, {@code admin-mfa} and {@code admin-session} through the real
 * chain and the real container; the client is one {@code X-Forwarded-For} address, which the
 * harness believes because the loopback is its trusted proxy.
 */
class RateLimitIndependenceTest {

    private static final String ROOT = "confia.web.rate-limit.";
    private static final String TRUST_LOOPBACK = "confia.web.trusted-proxies=127.0.0.1,::1";
    private static final String FORWARDED = "X-Forwarded-For";
    private static final String CLIENT = "203.0.113.71";

    private static HttpResponse<String> call(HarnessProcess process, String path) {
        return process.get(path, FORWARDED, CLIENT);
    }

    @Test
    void aClientWhoseLoginQuotaIsSpentIsStillAdmittedByTheOtherTwoPolicies() {
        try (HarnessProcess process = HarnessProcess.start(TRUST_LOOPBACK,
                ROOT + "admin-login.request-limit=1")) {
            assertThat(call(process, "/test/limited").statusCode()).isEqualTo(200);
            // Non-vacuous: the login quota is really spent for this client.
            HttpResponse<String> spent = call(process, "/test/limited");
            assertThat(spent.statusCode()).isEqualTo(429);
            assertThat(spent.headers().firstValue("Retry-After")).isPresent();

            assertThat(call(process, "/test/limited-session").statusCode()).isEqualTo(200);
            assertThat(call(process, "/test/limited-mfa").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void aClientWhoseSecondFactorQuotaIsSpentIsStillAdmittedByTheOtherTwoPolicies() {
        try (HarnessProcess process = HarnessProcess.start(TRUST_LOOPBACK,
                ROOT + "admin-mfa.request-limit=1")) {
            assertThat(call(process, "/test/limited-mfa").statusCode()).isEqualTo(200);
            assertThat(call(process, "/test/limited-mfa").statusCode()).isEqualTo(429);

            assertThat(call(process, "/test/limited").statusCode()).isEqualTo(200);
            assertThat(call(process, "/test/limited-session").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void aClientWhoseSessionQuotaIsSpentIsStillAdmittedByTheOtherTwoPolicies() {
        try (HarnessProcess process = HarnessProcess.start(TRUST_LOOPBACK,
                ROOT + "admin-session.request-limit=2")) {
            assertThat(call(process, "/test/limited-session").statusCode()).isEqualTo(200);
            assertThat(call(process, "/test/limited-session").statusCode()).isEqualTo(200);
            assertThat(call(process, "/test/limited-session").statusCode()).isEqualTo(429);

            assertThat(call(process, "/test/limited").statusCode()).isEqualTo(200);
            assertThat(call(process, "/test/limited-mfa").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void eachPolicyAppliesItsOwnConfiguredLimit() {
        try (HarnessProcess process = HarnessProcess.start(TRUST_LOOPBACK,
                ROOT + "admin-login.request-limit=1", ROOT + "admin-mfa.request-limit=2",
                ROOT + "admin-session.request-limit=3")) {
            assertThat(countAdmitted(process, "/test/limited")).isEqualTo(1);
            assertThat(countAdmitted(process, "/test/limited-mfa")).isEqualTo(2);
            assertThat(countAdmitted(process, "/test/limited-session")).isEqualTo(3);
        }
    }

    private static int countAdmitted(HarnessProcess process, String path) {
        int admitted = 0;
        while (admitted < 5 && call(process, path).statusCode() == 200) {
            admitted++;
        }
        return admitted;
    }
}
