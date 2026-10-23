package com.confia.identity.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.InstitutionId;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Condition H1 (docs/03-seguridad.md section 4.4; session-endpoints-cookie-and-csrf design.md
 * decision 11; scenarios I160 to I163): the login is reachable only under the real {@code
 * admin-login} policy, so the limiter answers before any transaction opens and before Argon2id
 * runs. The process is the production one with the real policy; the second process raises only the
 * request limit of layer 1, so that what stops the client in the failure scenarios is layer 2 and
 * nothing else. Each test uses a client address of its own.
 */
class LoginRateLimitClosesH1IT extends LoginEndpointSupport {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static final String PENDING = "pendiente.enrolar@colegio.edu.hn";
    private static final String ANA = "ana.martinez@colegio.edu.hn";

    private static LoginProcess process;
    private static LoginProcess layerTwoOnly;

    @BeforeAll
    static void startTheProcesses() {
        process = LoginProcess.start(PEPPER_BYTES, INSTITUTION, TEST_PASSWORD, Map.of());
        layerTwoOnly = LoginProcess.start(PEPPER_BYTES, INSTITUTION, TEST_PASSWORD,
                Map.of("confia.web.rate-limit.admin-login.request-limit", "100"));
    }

    @AfterAll
    static void stopTheProcesses() {
        layerTwoOnly.close();
        process.close();
    }

    @Override
    InstitutionId institution() {
        return INSTITUTION;
    }

    /** I160: ten requests a minute reach the use case and the eleventh is refused before it. */
    @Test
    void tenLoginsAMinuteReachTheUseCaseAndTheEleventhIs429WithoutRunningIt() {
        seedAccount(PENDING, true, false);
        String address = LoginProcess.freshAddress();

        for (int attempt = 1; attempt <= 10; attempt++) {
            assertThat(process.login(address, PENDING, PASSWORD).statusCode())
                    .as("request %d reaches the use case", attempt).isEqualTo(200);
        }
        int entriesBefore = auditRows().size();
        long backoffBefore = count("identity_login_backoff");

        HttpResponse<String> eleventh = process.login(address, PENDING, PASSWORD);
        HttpResponse<String> twelfth = process.login(address, ANA, "incorrecta-1");

        assertProblem(eleventh, 429, "too-many-requests");
        assertThat(Long.parseLong(eleventh.headers().firstValue("Retry-After").orElseThrow()))
                .isPositive();
        assertProblem(twelfth, 429, "too-many-requests");
        assertThat(auditRows("identity.login.second_factor_enrollment_required")).hasSize(10);
        assertThat(auditRows()).as("the use case ran zero times: no entry, no transaction")
                .hasSize(entriesBefore);
        assertThat(count("identity_login_backoff")).isEqualTo(backoffBefore);
        assertThat(eleventh.headers().firstValue("WWW-Authenticate")).isEmpty();
    }

    /**
     * I161: ten rejected attempts, against one account and against identifiers that are not accounts,
     * turn layer 2 on; from there the rows and the counters of the backoff and the entries of
     * failure stop growing.
     */
    @Test
    void afterTenFailuresOfTheClientTheBackoffRowsAndTheFailureEntriesStopGrowing() {
        seedAccount(ANA, false, false);
        String address = LoginProcess.freshAddress();

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertProblem(layerTwoOnly.login(address, ANA, "incorrecta-" + attempt), 401,
                    "authentication-failed");
        }
        for (int stranger = 1; stranger <= 7; stranger++) {
            assertProblem(layerTwoOnly.login(address, "intruso" + stranger + "@colegio.edu.hn",
                    "incorrecta-1"), 401, "authentication-failed");
        }
        long backoffRows = count("identity_login_backoff");
        int failureEntries = auditRows("identity.login.failed").size();
        long highest = highestConsecutiveFailures();
        assertThat(backoffRows).as("one row per identifier tried").isEqualTo(8);
        assertThat(failureEntries).isEqualTo(10);
        assertThat(highest).isEqualTo(3);

        for (int more = 1; more <= 3; more++) {
            HttpResponse<String> refused = layerTwoOnly.login(address,
                    more == 1 ? ANA : "nuevo" + more + "@colegio.edu.hn", "incorrecta-9");
            assertProblem(refused, 429, "too-many-requests");
            assertThat(refused.headers().firstValue("Retry-After")).isPresent();
        }

        assertThat(count("identity_login_backoff")).as("no row was added").isEqualTo(backoffRows);
        assertThat(auditRows("identity.login.failed")).as("no failure was written")
                .hasSize(failureEntries);
        assertThat(highestConsecutiveFailures()).as("no counter moved").isEqualTo(highest);
    }

    /** I162: only a rejected attempt counts as a failure of the address. */
    @Test
    void twelveCorrectLoginsOfAccountsWithAPendingSecondFactorLeaveTheFailureCountAtZero() {
        seedAccount(PENDING, true, false);
        seedAccount("pendiente.verificar@colegio.edu.hn", true, true);
        String address = LoginProcess.freshAddress();

        for (int attempt = 1; attempt <= 12; attempt++) {
            String identifier = attempt % 2 == 0 ? PENDING : "pendiente.verificar@colegio.edu.hn";
            assertThat(layerTwoOnly.login(address, identifier, PASSWORD).statusCode())
                    .as("attempt %d is not restricted: no failure was counted", attempt)
                    .isEqualTo(200);
        }

        assertProblem(layerTwoOnly.login(address, PENDING, "incorrecta-1"), 401,
                "authentication-failed");
    }

    /** I163: a request refused by the origin filter does not use up the quota of the address. */
    @Test
    void aRequestWithAForeignOriginIs403AndTheQuotaOfTheAddressStaysAtNine() {
        seedAccount(PENDING, true, false);
        String address = LoginProcess.freshAddress();
        for (int attempt = 1; attempt <= 9; attempt++) {
            assertThat(process.login(address, PENDING, PASSWORD).statusCode()).isEqualTo(200);
        }

        HttpResponse<String> foreign = process.post(LoginProcess.jsonOf(PENDING, PASSWORD),
                "Origin", "https://evil.example", "X-Forwarded-For", address);

        assertProblem(foreign, 403, "csrf-invalid");
        assertThat(process.login(address, PENDING, PASSWORD).statusCode())
                .as("the tenth request of the minute is still admitted").isEqualTo(200);
        assertProblem(process.login(address, PENDING, PASSWORD), 429, "too-many-requests");
    }
}
