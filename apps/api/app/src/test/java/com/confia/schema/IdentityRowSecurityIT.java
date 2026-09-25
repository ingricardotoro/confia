package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Row-level security on <b>both</b> identity tables, by institution
 * (identity-module-and-password-authentication design.md decision 4;
 * specs/build-integrity/spec.md, requirements "Tablas nuevas de identidad, con
 * {@code institution_id} y seguridad de fila forzada" and "Permisos de acceso a las tablas nuevas
 * de identidad por rol de base de datos", both of which say "cada una de las dos").
 *
 * <p>{@code RolePrivilegeMatrixIT} confirms the catalogue-level shape of the grants (what {@code
 * has_table_privilege} reports); this class is the same distinction {@code
 * IdempotencyRowSecurityIT} and {@code IdempotencyKeyPrivilegeIT} already draw for
 * {@code shared_idempotency_key}, applied here: a catalogue assertion proves the policy and the
 * grants exist, only a real two-institution read and a real rejected statement prove they work.
 *
 * <p><b>Why {@code identity_login_backoff} is covered here and not left to the catalogue gates.</b>
 * The pre-merge security audit of this slice found it missing, and the omission was the same shape
 * change 6's review found for {@code shared_idempotency_key}: the isolation test gets written for
 * the table the change is named after, and the second table inherits nothing but the assumption
 * that an identical policy string behaves identically. {@code CLAUDE.md} allows no exception — every
 * row-level policy needs an integration test proving one user cannot read another's data — and this
 * is the more sensitive of the two tables, not the less: it holds a fingerprint per identifier
 * <em>presented</em>, including identifiers matching no account at all, so a leak across
 * institutions here would answer "was this address tried against that school" for an attacker who
 * never had an account to begin with.
 */
class IdentityRowSecurityIT extends CommittingPostgresIntegrationTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** A syntactically valid stand-in hash: satisfies the {@code $argon2id$} prefix CHECK only. */
    private static final String PLACEHOLDER_PASSWORD_HASH =
            "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g";

    @Test
    void oneInstitutionCannotReadAnotherInstitutionsStaffAccount() {
        UUID institutionA = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();
        insertOneStaffAccount(institutionA, "maria.lopez@colegio-a.edu.hn");
        insertOneStaffAccount(institutionB, "juan.perez@colegio-b.edu.hn");

        long visibleToB = countVisibleStaffAccounts(institutionB, institutionA);
        assertThat(visibleToB)
                .as("institution B must see none of institution A's staff accounts, although the "
                        + "row physically exists: the row policy, not a query predicate, is what "
                        + "keeps them apart")
                .isZero();

        long ownRowVisibleToB = countVisibleStaffAccounts(institutionB, institutionB);
        assertThat(ownRowVisibleToB)
                .as("institution B must still see its own staff account: a policy that hid "
                        + "everything from everyone would satisfy the assertion above for the "
                        + "wrong reason")
                .isEqualTo(1L);
    }

    @Test
    void anAbsentInstitutionContextReturnsZeroRowsNotAPermissionError() {
        UUID institution = UUID.randomUUID();
        insertOneStaffAccount(institution, "nadie.especial@colegio.edu.hn");

        long directCount = transactionRunner().execute(contextWithNoInstitution(),
                () -> dsl.fetchOne("select count(*) as c from identity_staff_account")
                        .get("c", Number.class).longValue());

        assertThat(directCount)
                .as("with no app.institution_id set, the policy must deny by returning zero rows, "
                        + "never by raising a permission error")
                .isZero();
    }

    @Test
    void confiaPortalAppIsRejectedOnAllFourOperations() throws SQLException {
        UUID institutionId = UUID.randomUUID();
        String email = "portal.probe@colegio.edu.hn";

        try (Connection connection = SharedPostgresContainer.connectionAs("confia_portal_app")) {
            connection.setAutoCommit(false);

            setInstitutionContext(connection, institutionId);
            assertRejected(connection, "identity_staff_account",
                    "select count(*) from identity_staff_account where institution_id = ?",
                    institutionId);
            connection.rollback();

            setInstitutionContext(connection, institutionId);
            assertRejected(connection, "identity_staff_account", """
                    insert into identity_staff_account (institution_id, id, email, password_hash)
                    values (?, ?, ?, ?)
                    """, institutionId, UUID.randomUUID(), email, PLACEHOLDER_PASSWORD_HASH);
            connection.rollback();

            setInstitutionContext(connection, institutionId);
            assertRejected(connection, "identity_staff_account", """
                    update identity_staff_account set password_hash = ?
                    where institution_id = ? and email = ?
                    """, PLACEHOLDER_PASSWORD_HASH, institutionId, email);
            connection.rollback();

            setInstitutionContext(connection, institutionId);
            assertRejected(connection, "identity_staff_account",
                    "delete from identity_staff_account where institution_id = ? and email = ?",
                    institutionId, email);
            connection.rollback();
        }
    }

    @Test
    void oneInstitutionCannotReadAnotherInstitutionsLoginBackoff() {
        UUID institutionA = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();
        String sharedFingerprint = someIdentifierHash();
        insertOneLoginBackoff(institutionA, sharedFingerprint);
        insertOneLoginBackoff(institutionB, sharedFingerprint);

        long visibleToB = countVisibleLoginBackoff(institutionB, institutionA);
        assertThat(visibleToB)
                .as("institution B must see none of institution A's backoff rows. The fingerprint "
                        + "is deliberately the same value in both institutions' reach here: the "
                        + "primary key alone would not keep them apart, so what is being proven is "
                        + "the policy")
                .isZero();

        long ownRowVisibleToB = countVisibleLoginBackoff(institutionB, institutionB);
        assertThat(ownRowVisibleToB)
                .as("institution B must still see its own backoff row: a policy that hid "
                        + "everything from everyone would satisfy the assertion above for the "
                        + "wrong reason")
                .isEqualTo(1L);
    }

    @Test
    void anAbsentInstitutionContextReturnsZeroBackoffRowsNotAPermissionError() {
        insertOneLoginBackoff(UUID.randomUUID(), someIdentifierHash());

        long directCount = transactionRunner().execute(contextWithNoInstitution(),
                () -> dsl.fetchOne("select count(*) as c from identity_login_backoff")
                        .get("c", Number.class).longValue());

        assertThat(directCount)
                .as("with no app.institution_id set, the backoff policy must deny by returning zero "
                        + "rows, never by raising a permission error")
                .isZero();
    }

    @Test
    void confiaPortalAppIsRejectedOnAllFourOperationsOnLoginBackoff() throws SQLException {
        UUID institutionId = UUID.randomUUID();
        String fingerprint = someIdentifierHash();

        try (Connection connection = SharedPostgresContainer.connectionAs("confia_portal_app")) {
            connection.setAutoCommit(false);

            setInstitutionContext(connection, institutionId);
            assertRejected(connection, "identity_login_backoff",
                    "select count(*) from identity_login_backoff where institution_id = ?",
                    institutionId);
            connection.rollback();

            setInstitutionContext(connection, institutionId);
            assertRejected(connection, "identity_login_backoff", """
                    insert into identity_login_backoff
                        (institution_id, identifier_hash, consecutive_failures, last_attempt_at)
                    values (?, ?, 1, now())
                    """, institutionId, fingerprint);
            connection.rollback();

            setInstitutionContext(connection, institutionId);
            assertRejected(connection, "identity_login_backoff", """
                    update identity_login_backoff set consecutive_failures = 2
                    where institution_id = ? and identifier_hash = ?
                    """, institutionId, fingerprint);
            connection.rollback();

            setInstitutionContext(connection, institutionId);
            assertRejected(connection, "identity_login_backoff",
                    "delete from identity_login_backoff where institution_id = ? "
                            + "and identifier_hash = ?",
                    institutionId, fingerprint);
            connection.rollback();
        }
    }

    /**
     * A syntactically valid stand-in for the keyed fingerprint: 64 lowercase hex characters, which
     * is all {@code identity_login_backoff_hash_chk} enforces. It is deliberately not a real
     * fingerprint of anything — the pepper that derives one does not exist until PR C2 — and it must
     * not be, because a value derived from a real address would put that address in this file.
     */
    private static String someIdentifierHash() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }

    private void insertOneLoginBackoff(UUID institutionId, String identifierHash) {
        transactionRunner().execute(contextOf(institutionId), () -> {
            dsl.execute("""
                    insert into identity_login_backoff
                        (institution_id, identifier_hash, consecutive_failures, last_attempt_at)
                    values (?, ?, 1, now())
                    """, institutionId, identifierHash);
            return null;
        });
    }

    private long countVisibleLoginBackoff(UUID contextInstitutionId, UUID queryInstitutionId) {
        return transactionRunner().execute(contextOf(contextInstitutionId),
                () -> dsl.fetchOne(
                                "select count(*) as c from identity_login_backoff "
                                        + "where institution_id = ?",
                                queryInstitutionId)
                        .get("c", Number.class).longValue());
    }

    private void insertOneStaffAccount(UUID institutionId, String email) {
        transactionRunner().execute(contextOf(institutionId), () -> {
            dsl.execute("""
                    insert into identity_staff_account (institution_id, id, email, password_hash)
                    values (?, ?, ?, ?)
                    """, institutionId, UUID.randomUUID(), email, PLACEHOLDER_PASSWORD_HASH);
            return null;
        });
    }

    private long countVisibleStaffAccounts(UUID contextInstitutionId, UUID queryInstitutionId) {
        return transactionRunner().execute(contextOf(contextInstitutionId),
                () -> dsl.fetchOne(
                                "select count(*) as c from identity_staff_account "
                                        + "where institution_id = ?",
                                queryInstitutionId)
                        .get("c", Number.class).longValue());
    }

    private static void setInstitutionContext(Connection connection, UUID institutionId)
            throws SQLException {
        try (PreparedStatement statement = connection
                .prepareStatement("select set_config('app.institution_id', ?, true)")) {
            statement.setString(1, institutionId.toString());
            statement.execute();
        }
    }

    private static void assertRejected(Connection connection, String table, String sql,
            Object... params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            assertThatThrownBy(statement::execute)
                    .as("confia_portal_app must have no privilege on %s", table)
                    .isInstanceOf(SQLException.class)
                    .extracting(thrown -> ((SQLException) thrown).getSQLState())
                    .isEqualTo(INSUFFICIENT_PRIVILEGE);
        }
    }

    private static SecurityContext contextOf(UUID institutionId) {
        return new SecurityContext("", "system", institutionId.toString(),
                UUID.randomUUID().toString());
    }

    /** No {@code institutionId} at all: {@code TransactionRunner} never sets {@code app.institution_id}. */
    private static SecurityContext contextWithNoInstitution() {
        return new SecurityContext("", "system", "", UUID.randomUUID().toString());
    }
}
