package com.confia.identity.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.TotpAlgorithm;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.token.MfaPurpose;
import com.confia.support.SharedPostgresContainer;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
 * {@code POST /api/v1/auth/mfa/enrollments} and {@code POST /api/v1/auth/mfa/verifications} on the real
 * administrative process, against a real PostgreSQL (session-endpoints-cookie-and-csrf design.md,
 * decisions 6, 13 and 18; scenarios I170 to I174, I180 to I184, I221, I222 and WE72). Every token is
 * the one the login endpoint hands out or one the process's own issuer signs, every secret is the one
 * an enrollment answers, and every code is computed from that secret with the production algorithm.
 * Each test chooses the client address of its requests, so no test shares a bucket of the limiter.
 */
class SecondFactorEndpointsIT extends LoginEndpointSupport {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static final String ENROLLMENTS = "/api/v1/auth/mfa/enrollments";
    private static final String VERIFICATIONS = "/api/v1/auth/mfa/verifications";
    private static final String SESSION_COOKIE = "__Secure-confia_admin_sid";
    private static final String CSRF_COOKIE = "__Host-confia_csrf";
    private static final Duration PERIOD = Duration.ofSeconds(30);

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
        assertProblem(verify(anaToken, "{\"code\":\"123456\"}", LoginProcess.freshAddress()),
                401, "token-invalid");
        assertProblem(verify(expired, "{\"code\":\"123456\"}", LoginProcess.freshAddress()), 401,
                "token-expired");
        assertThat(count("identity_mfa_totp_credential") - credentialsBaseline).isZero();
        assertThat(count("identity_refresh_token_family")).as("only the login of the fixture").isEqualTo(families);
    }

    @Test
    void withoutACredentialTheTwoRoutesAnswerTheUniformDenial() {
        HttpResponse<String> enrollment = process.postTo(ENROLLMENTS, null, "{}",
                "X-Forwarded-For", LoginProcess.freshAddress());
        HttpResponse<String> verification = process.postTo(VERIFICATIONS, null,
                "{\"code\":\"123456\"}", "X-Forwarded-For", LoginProcess.freshAddress());

        assertProblem(enrollment, 401, "authentication-required");
        assertProblem(verification, 401, "authentication-required");
    }

    // --- I170, I168, I176: the first verification ---

    @Test
    void theFirstVerificationActivatesTheCredentialOpensTheSessionAndEndsTheOtherOnes() {
        Account jorge = newAccount();
        IssuedFamily previous = jorge.openFamily();
        String token = jorge.enrollToken();
        String secret = json(enroll(token, LoginProcess.freshAddress())).get("secret").asString();

        HttpResponse<String> response = verify(token, "{\"code\":\"" + codeOf(secret, 0) + "\"}",
                LoginProcess.freshAddress());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("status", "accessToken",
                "accessTokenExpiresAt", "sessionExpiresAt", "idleExpiresAt");
        assertThat(body.get("status").asString()).isEqualTo("authenticated");
        AuthenticatedActor actor = process.verifier().verifyAccess(body.get("accessToken").asString());
        assertThat(actor.accountId()).isEqualTo(jorge.id.value());
        assertThat(actor.methods()).containsExactlyInAnyOrder(AuthenticationMethod.PASSWORD,
                AuthenticationMethod.ONE_TIME_PASSWORD);
        List<String> cookies = response.headers().allValues("Set-Cookie");
        assertThat(cookies).hasSize(2);
        assertThat(cookies).anyMatch(cookie -> cookie.startsWith(SESSION_COOKIE + "="));
        assertThat(cookies).anyMatch(cookie -> cookie.startsWith(CSRF_COOKIE + "="));
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
        assertThat(familyOf(actor.sessionId()).get("second_factor_verified", Boolean.class)).isTrue();
        Record old = familyOf(previous.sessionId());
        assertThat(old.get("revoked_reason", String.class)).isEqualTo("mfa_change");
        assertThat(familyOf(actor.sessionId()).get("revoked_at")).isNull();
        assertThat(credentialOf(jorge.id).get("last_accepted_counter", Long.class))
                .isGreaterThanOrEqualTo(0L);
        assertThat(auditRows("identity.mfa.activated")).hasSize(1);
        assertThat(auditRows("identity.session.revoked")).singleElement().satisfies(row ->
                assertThat(json(row.afterValue())).isEqualTo(JSON.readTree(
                        "{\"reason\":\"mfa_change\",\"families\":1}")));
        assertThat(jorge.loginStatus()).as("[I168] the next login owes the second factor")
                .isEqualTo("second-factor-required");
    }

    // --- I170, I171: a later verification ---

    @Test
    void aLaterVerificationOpensASessionWithATotpCodeOrWithARecoveryCodeUsedOnce() {
        Account sofia = newAccount();
        JsonNode enrolled = completeFirstVerification(sofia);
        String secret = enrolled.get("secret").asString();
        String recoveryCode = recoveryCodesOf(enrolled).get(0);

        String withTotp = sofia.verifyToken();
        HttpResponse<String> totp = verify(withTotp, "{\"code\":\"" + codeOf(secret, 1) + "\"}",
                LoginProcess.freshAddress());
        String withRecovery = sofia.verifyToken();
        HttpResponse<String> recovery = verify(withRecovery,
                "{\"recoveryCode\":\"" + recoveryCode + "\"}", LoginProcess.freshAddress());
        HttpResponse<String> again = verify(sofia.verifyToken(),
                "{\"recoveryCode\":\"" + recoveryCode + "\"}", LoginProcess.freshAddress());

        assertThat(totp.statusCode()).isEqualTo(200);
        assertThat(recovery.statusCode()).isEqualTo(200);
        assertThat(process.verifier().verifyAccess(json(recovery).get("accessToken").asString())
                .methods()).contains(AuthenticationMethod.ONE_TIME_PASSWORD);
        assertProblem(again, 401, "second-factor-invalid");
        assertThat(again.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(unusedRecoveryCodes(sofia.id)).isEqualTo(9L);
        assertThat(auditRows("identity.session.revoked")).as("only the activation revoked sessions")
                .hasSize(1);
    }

    // --- I172, I173 ---

    @Test
    void aWrongCodeIsSecondFactorInvalidWithTheBearerChallengeNoCookieAndAdvancesTheBackoff() {
        Account sofia = newAccount();
        completeFirstVerification(sofia);
        String wrong = "000000";

        HttpResponse<String> response = verify(sofia.verifyToken(), "{\"code\":\"" + wrong + "\"}",
                LoginProcess.freshAddress());

        assertProblem(response, 401, "second-factor-invalid");
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(json(response).get("detail").asString()).doesNotContain(wrong);
        assertThat(consecutiveFailuresOf(sofia.id)).isEqualTo(1L);
    }

    @Test
    void aBodyWithBothCodesWithNoneOrWithAnUnknownMemberIsValidationFailed() {
        Account jorge = newAccount();
        String token = jorge.verifyTokenWithoutLogin();
        String address = LoginProcess.freshAddress();

        for (String body : List.of("{\"code\":\"123456\",\"recoveryCode\":\"ABCDEFGHJK\"}", "{}",
                "{\"code\":\"123456\",\"extra\":true}", "{\"code\":\"" + "9".repeat(65) + "\"}")) {
            HttpResponse<String> response = verify(token, body, address);

            assertProblem(response, 400, "validation-failed");
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        }
        assertThat(count("identity_refresh_token_family") - familiesBaseline).isZero();
    }

    // --- I182, I222: the credential is already verified ---

    @Test
    void enrollingAVerifiedCredentialIsA409AndWritesOnlyTheRejection() {
        Account sofia = newAccount();
        JsonNode enrolled = completeFirstVerification(sofia);
        String secretBefore = credentialOf(sofia.id).get("encrypted_secret", String.class);
        long auditBefore = auditRows().size();

        HttpResponse<String> response = enroll(
                process.restrictedToken(sofia.id.value(), MfaPurpose.MFA_ENROLL),
                LoginProcess.freshAddress());

        assertProblem(response, 409, "mfa-already-enrolled");
        assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
        assertThat(response.body()).doesNotContain(enrolled.get("secret").asString());
        assertThat(credentialOf(sofia.id).get("encrypted_secret", String.class))
                .isEqualTo(secretBefore);
        assertThat(auditRows()).hasSize((int) auditBefore + 1);
        assertThat(auditRows("identity.mfa.enrollment_rejected")).hasSize(1);
    }

    @Test
    void anEnrollTokenOfAVerifiedCredentialProvesNothingWithATotpCodeOrARecoveryCode() {
        Account sofia = newAccount();
        JsonNode enrolled = completeFirstVerification(sofia);
        String enrollToken = process.restrictedToken(sofia.id.value(), MfaPurpose.MFA_ENROLL);
        long familiesBefore = count("identity_refresh_token_family");

        HttpResponse<String> withTotp = verify(enrollToken,
                "{\"code\":\"" + codeOf(enrolled.get("secret").asString(), 1) + "\"}",
                LoginProcess.freshAddress());
        HttpResponse<String> withRecovery = verify(enrollToken,
                "{\"recoveryCode\":\"" + recoveryCodesOf(enrolled).get(1) + "\"}",
                LoginProcess.freshAddress());

        assertProblem(withTotp, 401, "second-factor-invalid");
        assertProblem(withRecovery, 401, "second-factor-invalid");
        assertThat(count("identity_refresh_token_family")).isEqualTo(familiesBefore);
        assertThat(unusedRecoveryCodes(sofia.id)).isEqualTo(10L);
    }

    /** [I169], [I221]: the first verification accepts only a TOTP code. */
    @Test
    void theFirstVerificationRefusesARecoveryCodeAndLeavesTheCredentialUnverified() {
        Account jorge = newAccount();
        String token = jorge.enrollToken();
        JsonNode enrolled = json(enroll(token, LoginProcess.freshAddress()));

        HttpResponse<String> response = verify(token,
                "{\"recoveryCode\":\"" + recoveryCodesOf(enrolled).get(0) + "\"}",
                LoginProcess.freshAddress());

        assertProblem(response, 401, "second-factor-invalid");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(credentialOf(jorge.id).get("last_accepted_counter", Long.class)).isEqualTo(-1L);
        assertThat(unusedRecoveryCodes(jorge.id)).isEqualTo(10L);
        assertThat(count("identity_refresh_token_family") - familiesBaseline).isZero();
    }

    // --- WE72 ---

    @Test
    void theTenthSecondFactorInvalidActivatesTheRestrictionOfAdminMfa() {
        String address = LoginProcess.freshAddress();
        for (int i = 0; i < 10; i++) {
            Account account = newAccount();
            HttpResponse<String> failure = verify(account.verifyTokenWithoutLogin(),
                    "{\"code\":\"123456\"}", address);

            assertProblem(failure, 401, "second-factor-invalid");
        }

        HttpResponse<String> next = verify(newAccount().verifyTokenWithoutLogin(),
                "{\"code\":\"123456\"}", address);
        HttpResponse<String> following = verify(newAccount()
                .verifyTokenWithoutLogin(), "{\"code\":\"123456\"}", address);

        assertThat(next.statusCode()).as("the one attempt a minute the second layer admits")
                .isIn(401, 429);
        assertProblem(following, 429, "too-many-requests");
        assertThat(Integer.parseInt(following.headers().firstValue("Retry-After").orElseThrow()))
                .isBetween(1, 60);
        assertThat(verify(newAccount().verifyTokenWithoutLogin(),
                "{\"code\":\"123456\"}", LoginProcess.freshAddress()).statusCode())
                .as("another client is not restricted").isEqualTo(401);
    }

    // --- Helpers ---

    private HttpResponse<String> enroll(String token, String address) {
        return process.postTo(ENROLLMENTS, token, null, "X-Forwarded-For", address);
    }

    private HttpResponse<String> verify(String token, String body, String address) {
        return process.postTo(VERIFICATIONS, token, body, "X-Forwarded-For", address);
    }

    /** Enrolls and verifies with the enroll token of the login: the credential is verified and one family exists. */
    private JsonNode completeFirstVerification(Account account) {
        String token = account.enrollToken();
        JsonNode enrolled = json(enroll(token, LoginProcess.freshAddress()));
        HttpResponse<String> verified = verify(token,
                "{\"code\":\"" + codeOf(enrolled.get("secret").asString(), 0) + "\"}",
                LoginProcess.freshAddress());
        assertThat(verified.statusCode()).as("the first verification of the fixture").isEqualTo(200);
        return enrolled;
    }

    /** The TOTP code of {@code base32} for the counter of now plus {@code ahead} periods. */
    private static String codeOf(String base32, long ahead) {
        long counter = TotpAlgorithm.counterFor(Instant.now(), PERIOD) + ahead;
        return TotpAlgorithm.generate(decodeBase32(base32), counter).value();
    }

    private static byte[] decodeBase32(String text) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        byte[] out = new byte[text.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (char c : text.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
            }
        }
        return out;
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

    private long consecutiveFailuresOf(StaffAccountId account) {
        return transactionRunner().execute(contextOf(INSTITUTION), () -> dsl.fetchSingle("""
                select coalesce(max(consecutive_failures), 0) from identity_mfa_totp_backoff
                 where institution_id = ? and account_id = ?
                """, INSTITUTION.value(), account.value()).get(0, Long.class));
    }

    /**
     * The flag is changed as the database owner: the role of the application may not change it, and
     * no endpoint does. It stands for an administrator who required the second factor after a session began.
     */
    private static void setMfaRequired(StaffAccountId account, boolean required) {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "update identity_staff_account set mfa_required = ? where institution_id = ? and id = ?")) {
            statement.setBoolean(1, required);
            statement.setObject(2, INSTITUTION.value());
            statement.setObject(3, account.value());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Record familyOf(UUID familyId) {
        return transactionRunner().execute(contextOf(INSTITUTION), () -> dsl.fetchSingle("""
                select account_id, second_factor_verified, revoked_at, revoked_reason
                  from identity_refresh_token_family where institution_id = ? and id = ?
                """, INSTITUTION.value(), familyId));
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

    /** A family opened by a real login, the session a first verification must end. */
    private record IssuedFamily(UUID sessionId) {
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

        /** The restricted token a login answers with once the credential is verified. */
        String verifyToken() {
            JsonNode body = loginBody();
            assertThat(body.get("status").asString()).isEqualTo("second-factor-required");
            return body.get("secondFactorToken").asString();
        }

        /** A token of purpose {@code mfa-verify} signed by the process, with no login behind it. */
        String verifyTokenWithoutLogin() {
            return process.restrictedToken(id.value(), MfaPurpose.MFA_VERIFY);
        }

        String loginStatus() {
            return loginBody().get("status").asString();
        }

        String accessToken() {
            JsonNode body = loginBody();
            assertThat(body.get("status").asString()).isEqualTo("authenticated");
            return body.get("accessToken").asString();
        }

        IssuedFamily openFamily() {
            // A session of an account that owes no second factor yet: the account is free of MFA
            // for this one login, then marked as requiring it, as an administrator would.
            setMfaRequired(id, false);
            JsonNode body = loginBody();
            setMfaRequired(id, true);
            UUID sessionId = process.verifier().verifyAccess(body.get("accessToken").asString())
                    .sessionId();
            return new IssuedFamily(sessionId);
        }

        private JsonNode loginBody() {
            HttpResponse<String> response = process.login(LoginProcess.freshAddress(), email, PASSWORD);
            assertThat(response.statusCode()).isEqualTo(200);
            return json(response);
        }
    }
}
