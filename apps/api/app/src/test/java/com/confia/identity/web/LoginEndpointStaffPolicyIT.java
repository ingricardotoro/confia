package com.confia.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.MfaPolicy;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import com.confia.support.StaffRoleFixtures;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /api/v1/auth/sessions} on the real administrative process exactly as production
 * assembles it, with {@link MfaPolicy#STAFF} and no test policy (rbac-permission-matrix-and-audit-
 * integration design.md D14; scenario I302, and I287 to I290 at the edge): rows that carry
 * no stored second factor hint still never obtain a session, with or without a role.
 */
class LoginEndpointStaffPolicyIT extends LoginEndpointSupport {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static LoginProcess process;

    @BeforeAll
    static void startTheProductionProcess() {
        process = LoginProcess.startWithoutExemptions(PEPPER_BYTES, INSTITUTION, TEST_PASSWORD,
                Map.of());
    }

    @AfterAll
    static void stopTheProcess() {
        process.close();
    }

    @Override
    InstitutionId institution() {
        return INSTITUTION;
    }

    @Override
    SecondFactorExemptions exemptions() {
        // A set no process reads: the helper registers exemptions here, and the process under test
        // (production, with MfaPolicy.STAFF) ignores them, which is what these tests rely on.
        return new SecondFactorExemptions();
    }

    @Test
    void theProcessHoldsNoTestPolicyAndTheStaffPolicyIsTheOnlyOne() {
        assertThat(process.context().getBeanNamesForType(SecondFactorExemptions.class)).isEmpty();
        assertThat(process.context().getBeansOfType(MfaPolicy.class)).hasSize(1)
                .allSatisfy((name, policy) -> assertThat(policy).isSameAs(MfaPolicy.STAFF));
    }

    @Test
    void anAccountWithARoleAndAVerifiedCredentialGetsTheRestrictedTokenAndNoCookie()
            throws SQLException {
        String email = "contabilidad." + UUID.randomUUID() + "@colegio.edu.hn";
        StaffAccountId account = seedAccount(email, true, true);
        assign(account, "accounting");

        HttpResponse<String> response = process.login(LoginProcess.freshAddress(), email, PASSWORD);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("status").asString()).isEqualTo("second-factor-required");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(count("identity_refresh_token_family")).isZero();
    }

    @Test
    void anAccountWithNoRoleAndNoCredentialGetsTheEnrollmentTokenAndNeverASession() {
        String email = "sinrol." + UUID.randomUUID() + "@colegio.edu.hn";
        seedAccount(email, true, false);

        HttpResponse<String> response = process.login(LoginProcess.freshAddress(), email, PASSWORD);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("status").asString()).isNotEqualTo("authenticated")
                .isEqualTo("second-factor-enrollment-required");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(json(response).has("accessToken")).isFalse();
    }

    @Test
    void anAccountWithNoRoleAndAVerifiedCredentialOwesTheCode() {
        String email = "sinrolcodigo." + UUID.randomUUID() + "@colegio.edu.hn";
        seedAccount(email, true, true);

        HttpResponse<String> response = process.login(LoginProcess.freshAddress(), email, PASSWORD);

        assertThat(json(response).get("status").asString()).isEqualTo("second-factor-required");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    private void assign(StaffAccountId account, String roleCode) throws SQLException {
        try (Connection connection = StaffRoleFixtures.ownerTransaction(INSTITUTION.value())) {
            StaffRoleFixtures.assign(connection, INSTITUTION.value(), account.value(), roleCode);
            connection.commit();
        }
    }
}
