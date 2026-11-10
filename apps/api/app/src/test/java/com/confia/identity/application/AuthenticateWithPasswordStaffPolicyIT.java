package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.AuthenticationResult;
import com.confia.identity.domain.AuthenticationResult.Authenticated;
import com.confia.identity.domain.AuthenticationResult.SecondFactorEnrollmentRequired;
import com.confia.identity.domain.AuthenticationResult.SecondFactorRequired;
import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.MfaPolicy;
import com.confia.identity.domain.PlainPassword;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StaffRole;
import com.confia.identity.infrastructure.Argon2Pepper;
import com.confia.identity.infrastructure.Argon2Profile;
import com.confia.identity.infrastructure.BouncyCastleArgon2PasswordHasher;
import com.confia.identity.infrastructure.HmacLoginIdentifierFingerprinter;
import com.confia.identity.infrastructure.JooqLoginBackoffStore;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.identity.infrastructure.JooqTotpCredentialRepository;
import com.confia.kernel.InstitutionId;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.StaffRoleFixtures;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The attacks on the derived second factor, with the real policy {@link MfaPolicy#STAFF} and rows
 * that carry no stored second factor hint at all
 * (rbac-permission-matrix-and-audit-integration design.md D14, owner decisions O4 and Q1;
 * scenarios I287 to I290, I301 and I302): whatever the row says and whatever role the account holds
 * or lost, a correct password is never enough to start a session.
 */
class AuthenticateWithPasswordStaffPolicyIT extends CommittingPostgresIntegrationTest {

    private static final String EMAIL = "contadora.perez@colegio.edu.hn";
    private static final String PASSWORD = "Correcta#2026-S5a";
    private static final String DUMMY_ENCRYPTED_TOTP_SECRET =
            "v1:00000000-0000-0000-0000-000000000000:aXY=:Y2lwaGVy:dGFn";
    private static final Argon2Pepper PEPPER =
            Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(new byte[32]));
    private static final BouncyCastleArgon2PasswordHasher HASHER =
            new BouncyCastleArgon2PasswordHasher(Argon2Profile.floor(), PEPPER);
    private static final HmacLoginIdentifierFingerprinter FINGERPRINTER =
            new HmacLoginIdentifierFingerprinter(PEPPER);

    private final InstitutionId institution = new InstitutionId(UUID.randomUUID());

    @Test
    void anAccountWithTheAccountingRoleAndAVerifiedCredentialOwesTheCode() throws SQLException {
        StaffAccountId account = seed();
        assign(account, "accounting");
        verifyCredential(account);

        AuthenticationResult result = login("2026-11-09T10:00:00Z");

        assertThat(result).isInstanceOf(SecondFactorRequired.class);
        assertThat(((SecondFactorRequired) result).userId()).isEqualTo(account);
    }

    @Test
    void anAccountWithNoRoleAndNoCredentialOwesTheEnrollmentAndIsNeverAuthenticated() {
        StaffAccountId account = seed();

        AuthenticationResult result = login("2026-11-09T10:05:00Z");

        assertThat(result).isNotInstanceOf(Authenticated.class)
                .isInstanceOf(SecondFactorEnrollmentRequired.class);
        assertThat(((SecondFactorEnrollmentRequired) result).userId()).isEqualTo(account);
    }

    @Test
    void anAccountWithNoRoleAndAVerifiedCredentialOwesTheCode() {
        StaffAccountId account = seed();
        verifyCredential(account);

        AuthenticationResult result = login("2026-11-09T10:10:00Z");

        assertThat(result).isInstanceOf(SecondFactorRequired.class);
        assertThat(((SecondFactorRequired) result).userId()).isEqualTo(account);
    }

    @Test
    void removingTheRoleAssignmentDoesNotLiftTheRequirement() throws SQLException {
        StaffAccountId account = seed();
        assign(account, "accounting");
        verifyCredential(account);
        assertThat(login("2026-11-09T10:15:00Z")).isInstanceOf(SecondFactorRequired.class);

        unassign(account);

        assertThat(login("2026-11-09T10:16:00Z")).isInstanceOf(SecondFactorRequired.class);
    }

    @ParameterizedTest
    @EnumSource(StaffRole.class)
    void everyRoleOwesTheCode(StaffRole role) throws SQLException {
        StaffAccountId account = seed();
        assign(account, role.code());
        verifyCredential(account);

        assertThat(login("2026-11-09T10:20:00Z")).isInstanceOf(SecondFactorRequired.class);
    }

    @Test
    void theRetiredColumnSetToTrueGivesTheSameOutcomeAsSetToFalse() {
        seed();

        assertThat(login("2026-11-09T10:25:00Z")).isInstanceOf(SecondFactorEnrollmentRequired.class);
    }

    private AuthenticationResult login(String instant) {
        AuthenticateWithPassword useCase = new AuthenticateWithPassword(transactionRunner(),
                () -> institution, new JooqStaffAccountRepository(dsl),
                new JooqTotpCredentialRepository(dsl), new JooqLoginBackoffStore(dsl), HASHER,
                FINGERPRINTER, new JooqAuditLogWriter(dsl), MfaPolicy.STAFF,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
        return useCase.execute(contextOf(), new AuthenticationCommand(EMAIL, PASSWORD)).result();
    }

    /** The account row: nothing on it says whether the account owes a second factor. */
    private StaffAccountId seed() {
        StaffAccountId account = new StaffAccountId(UUID.randomUUID());
        String hash = HASHER.hash(PlainPassword.of(PASSWORD)).value();
        transactionRunner().execute(contextOf(), () -> dsl.execute("""
                insert into identity_staff_account
                    (institution_id, id, email, password_hash)
                values (?, ?, ?, ?)
                """, institution.value(), account.value(), LoginIdentifier.of(EMAIL).value(), hash));
        return account;
    }

    private void verifyCredential(StaffAccountId account) {
        transactionRunner().execute(contextOf(), () -> {
            JooqTotpCredentialRepository credentials = new JooqTotpCredentialRepository(dsl);
            credentials.insert(institution, account, DUMMY_ENCRYPTED_TOTP_SECRET);
            credentials.acceptCounter(institution, account, 58_204L);
            return null;
        });
    }

    private void assign(StaffAccountId account, String roleCode) throws SQLException {
        try (Connection connection = StaffRoleFixtures.ownerTransaction(institution.value())) {
            StaffRoleFixtures.assign(connection, institution.value(), account.value(), roleCode);
            connection.commit();
        }
    }

    private void unassign(StaffAccountId account) throws SQLException {
        try (Connection connection = StaffRoleFixtures.ownerTransaction(institution.value());
                PreparedStatement statement = connection.prepareStatement("""
                        delete from identity_staff_account_role
                         where institution_id = ? and account_id = ?
                        """)) {
            statement.setObject(1, institution.value());
            statement.setObject(2, account.value());
            statement.executeUpdate();
            connection.commit();
        }
    }

    private SecurityContext contextOf() {
        return new SecurityContext("", "system", institution.value().toString(),
                UUID.randomUUID().toString());
    }
}
