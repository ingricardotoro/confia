package com.confia.identity.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.token.MfaPurpose;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /api/v1/auth/mfa/enrollments} on the real
 * administrative process, against a real PostgreSQL (session-endpoints-cookie-and-csrf design.md,
 * decisions 6, 13 and 18; scenarios I170 to I174, I180 to I184, I221, I222 and WE72). Every token is
 * the one the login endpoint hands out or one the process's own issuer signs, every secret is the one
 * an enrollment answers, and every code is computed from that secret with the production algorithm.
 * Each test chooses the client address of its requests, so no test shares a bucket of the limiter.
 */
class SecondFactorEndpointsIT extends LoginEndpointSupport {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static final String ENROLLMENTS = "/api/v1/auth/mfa/enrollments";

    private static LoginProcess process;

    private long familiesBaseline;
    private long credentialsBaseline;

    @BeforeEach
    void rememberTheTables() {
        familiesBaseline = count("identity_refresh_token_family");
        credentialsBaseline = count("identity_mfa_totp_credential");
    }

    @BeforeAll
    static void startTheProcess() {
        process = LoginProcess.start(PEPPER_BYTES, INSTITUTION, TEST_PASSWORD, Map.of());
    }

    @AfterAll
    static void stopTheProcess() {
        process.close();
    }

    @Override
    InstitutionId institution() {
        return INSTITUTION;
    }

    // --- I180 and I181: the enrollment ---

    @Test
    void theFirstEnrollmentAnswers201WithTheSecretAndTenCodesAndNoCookie() {
        Account jorge = newAccount();
        String token = jorge.enrollToken();
        long sessionsBefore = process.createdHttpSessions();

        HttpResponse<String> response = enroll(token, LoginProcess.freshAddress());

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json(response);
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("secret", "recoveryCodes");
        assertThat(body.get("secret").asString()).matches("[A-Z2-7]{32}");
        List<String> codes = recoveryCodesOf(body);
        assertThat(codes).hasSize(10).doesNotHaveDuplicates().allSatisfy(code ->
                assertThat(code).hasSize(10));
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(process.createdHttpSessions()).as("no HTTP session was created")
                .isEqualTo(sessionsBefore);
        assertThat(count("identity_refresh_token_family") - familiesBaseline).as("an enrollment opens no session")
                .isZero();
        Record credential = credentialOf(jorge.id);
        assertThat(credential.get("encrypted_secret", String.class)).startsWith("v1:")
                .doesNotContain(body.get("secret").asString());
        assertThat(credential.get("last_accepted_counter", Long.class)).isEqualTo(-1L);
        assertThat(auditRows("identity.mfa.enrolled")).hasSize(1);
        assertThat(auditRows().toString()).as("no secret and no code reached the trail")
                .doesNotContain(body.get("secret").asString()).doesNotContain(codes.get(0));
    }

    @Test
    void enrollingAgainBeforeTheFirstVerificationReplacesTheSecretAndRetiresTheCodes() {
        Account jorge = newAccount();
        String token = jorge.enrollToken();
        JsonNode first = json(enroll(token, LoginProcess.freshAddress()));

        HttpResponse<String> response = enroll(token, LoginProcess.freshAddress());

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode second = json(response);
        assertThat(second.get("secret").asString()).isNotEqualTo(first.get("secret").asString());
        assertThat(recoveryCodesOf(second)).doesNotContainAnyElementsOf(recoveryCodesOf(first));
        assertThat(unusedRecoveryCodes(jorge.id)).as("only the ten of the second enrollment")
                .isEqualTo(10L);
        assertThat(auditRows("identity.mfa.enrolled")).hasSize(2);
    }

    // --- I184 and I174: who may use each route ---

    @Test
    void aVerifyTokenCannotEnrollAndAnAccessTokenCannotEitherNorCanAnExpiredOne() {
        Account jorge = newAccount();
        Account ana = newAccountWithoutMfa();
        String anaToken = ana.accessToken();
        long families = count("identity_refresh_token_family");
        String verifyToken = process.restrictedToken(jorge.id.value(), MfaPurpose.MFA_VERIFY);
        String expired = process.restrictedTokenAt(jorge.id.value(), MfaPurpose.MFA_ENROLL,
                Instant.now().minus(Duration.ofHours(1)));

        assertProblem(enroll(verifyToken, LoginProcess.freshAddress()), 401, "token-invalid");
        assertProblem(enroll(anaToken, LoginProcess.freshAddress()), 401, "token-invalid");
        assertProblem(enroll(expired, LoginProcess.freshAddress()), 401, "token-expired");
        assertThat(count("identity_mfa_totp_credential") - credentialsBaseline).isZero();
        assertThat(count("identity_refresh_token_family")).as("only the login of the fixture").isEqualTo(families);
    }

    @Test
    void withoutACredentialTheEnrollmentAnswersTheUniformDenial() {
        HttpResponse<String> enrollment = process.postTo(ENROLLMENTS, null, "{}",
                "X-Forwarded-For", LoginProcess.freshAddress());

        assertProblem(enrollment, 401, "authentication-required");
    }

    // --- I170, I168, I176: the first verification ---

    // --- I170, I171: a later verification ---

    // --- I172, I173 ---

    // --- I182, I222: the credential is already verified ---

    // --- WE72 ---

    // --- Helpers ---

    private HttpResponse<String> enroll(String token, String address) {
        return process.postTo(ENROLLMENTS, token, null, "X-Forwarded-For", address);
    }

    private static List<String> recoveryCodesOf(JsonNode body) {
        List<String> codes = new ArrayList<>();
        body.get("recoveryCodes").forEach(code -> codes.add(code.asString()));
        return codes;
    }

    private static List<String> fieldNames(JsonNode body) {
        List<String> names = new ArrayList<>();
        body.propertyNames().forEach(names::add);
        return names;
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    private Record credentialOf(StaffAccountId account) {
        return transactionRunner().execute(contextOf(INSTITUTION), () -> dsl.fetchSingle("""
                select encrypted_secret, last_accepted_counter
                  from identity_mfa_totp_credential where institution_id = ? and account_id = ?
                """, INSTITUTION.value(), account.value()));
    }

    private long unusedRecoveryCodes(StaffAccountId account) {
        return transactionRunner().execute(contextOf(INSTITUTION), () -> dsl.fetchSingle("""
                select count(*) from identity_mfa_recovery_code
                 where institution_id = ? and account_id = ? and used_at is null
                """, INSTITUTION.value(), account.value()).get(0, Long.class));
    }

    private static String freshEmail() {
        return "mfa." + UUID.randomUUID() + "@colegio.edu.hn";
    }

    private Account newAccount() {
        String email = freshEmail();
        return new Account(email, seedAccount(email, true, false));
    }

    private Account newAccountWithoutMfa() {
        String email = freshEmail();
        return new Account(email, seedAccount(email, false, false));
    }

    /** An account of the process and what a test does with it through the login endpoint. */
    private final class Account {

        private final String email;
        private final StaffAccountId id;

        Account(String email, StaffAccountId id) {
            this.email = email;
            this.id = id;
        }

        /** The restricted token a login answers with while the credential was never verified. */
        String enrollToken() {
            JsonNode body = loginBody();
            assertThat(body.get("status").asString()).isEqualTo("second-factor-enrollment-required");
            return body.get("secondFactorToken").asString();
        }

        String accessToken() {
            JsonNode body = loginBody();
            assertThat(body.get("status").asString()).isEqualTo("authenticated");
            return body.get("accessToken").asString();
        }

        private JsonNode loginBody() {
            HttpResponse<String> response = process.login(LoginProcess.freshAddress(), email, PASSWORD);
            assertThat(response.statusCode()).isEqualTo(200);
            return json(response);
        }
    }
}
