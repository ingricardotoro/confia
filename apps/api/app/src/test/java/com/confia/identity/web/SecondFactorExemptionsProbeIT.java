package com.confia.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.MfaPolicy;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Probe S1 of rbac-permission-matrix-and-audit-integration (design.md, D14): the test policy of the
 * second factor reaches the real administrative process through {@code spring.main.sources}, each
 * process context holds its own exemptions, and a process started without the test configuration is
 * the production one. If this probe fails for a reason of mechanism, D14 is wrong and task 5 stops
 * (the alternative is an {@code ApplicationContextInitializer} of the test tree, never a new
 * production parameter).
 *
 * <p>Both processes of the first two tests share one institution and one database, so one seeded
 * account is the same account for both; only the exemptions differ.
 */
class SecondFactorExemptionsProbeIT extends LoginEndpointSupport {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static final String SESSION_COOKIE = "__Secure-confia_admin_sid";
    private static final String CSRF_COOKIE = "__Host-confia_csrf";

    private static LoginProcess first;
    private static LoginProcess second;

    @BeforeAll
    static void startTheTwoProcesses() {
        first = LoginProcess.start(PEPPER_BYTES, INSTITUTION, TEST_PASSWORD, Map.of());
        second = LoginProcess.start(PEPPER_BYTES, INSTITUTION, TEST_PASSWORD, Map.of());
    }

    @AfterAll
    static void stopTheTwoProcesses() {
        second.close();
        first.close();
    }

    @Override
    InstitutionId institution() {
        return INSTITUTION;
    }

    @Override
    SecondFactorExemptions exemptions() {
        return first.exemptions();
    }

    @Test
    void anExemptAccountGetsTheSessionAndAnAccountThatIsNotGetsTheRestrictedToken() {
        String exempt = "exenta." + UUID.randomUUID() + "@colegio.edu.hn";
        String owing = "obligada." + UUID.randomUUID() + "@colegio.edu.hn";
        seedAccount(exempt, true, true);
        seedAccount(owing, false, true);

        HttpResponse<String> session = first.login(LoginProcess.freshAddress(), exempt, PASSWORD);
        HttpResponse<String> restricted = first.login(LoginProcess.freshAddress(), owing, PASSWORD);

        assertThat(session.statusCode()).isEqualTo(200);
        assertThat(json(session).get("status").asString()).isEqualTo("authenticated");
        List<String> cookies = session.headers().allValues("Set-Cookie");
        assertThat(cookies).hasSize(2);
        assertThat(cookies).anyMatch(cookie -> cookie.startsWith(SESSION_COOKIE + "="));
        assertThat(cookies).anyMatch(cookie -> cookie.startsWith(CSRF_COOKIE + "="));
        assertThat(restricted.statusCode()).isEqualTo(200);
        assertThat(json(restricted).get("status").asString()).isEqualTo("second-factor-required");
        assertThat(restricted.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void anAccountExemptInOneProcessOwesTheSecondFactorInAnother() {
        String email = "compartida." + UUID.randomUUID() + "@colegio.edu.hn";
        StaffAccountId account = seedAccount(email, true, true);

        HttpResponse<String> inTheSecond = second.login(LoginProcess.freshAddress(), email, PASSWORD);
        HttpResponse<String> inTheFirst = first.login(LoginProcess.freshAddress(), email, PASSWORD);

        assertThat(first.exemptions().requiresSecondFactor(account)).isFalse();
        assertThat(second.exemptions().requiresSecondFactor(account)).isTrue();
        assertThat(json(inTheSecond).get("status").asString()).isEqualTo("second-factor-required");
        assertThat(json(inTheFirst).get("status").asString()).isEqualTo("authenticated");
    }

    @Test
    void aProcessStartedWithoutTheConfigurationHoldsNoTestPolicyAndTheStaffOne() {
        try (LoginProcess production = LoginProcess.startWithoutExemptions(PEPPER_BYTES,
                INSTITUTION, TEST_PASSWORD, Map.of())) {
            assertThat(production.context().getBeanNamesForType(SecondFactorExemptions.class))
                    .isEmpty();
            assertThat(production.context().getBeansOfType(MfaPolicy.class)).hasSize(1)
                    .allSatisfy((name, policy) -> assertThat(policy).isSameAs(MfaPolicy.STAFF));
        }
    }
}
