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
 * Row-level security on {@code identity_staff_account}, by institution
 * (identity-module-and-password-authentication design.md decision 4;
 * specs/build-integrity/spec.md, requirements "Tablas nuevas de identidad, con
 * {@code institution_id} y seguridad de fila forzada" and "Permisos de acceso a las tablas nuevas
 * de identidad por rol de base de datos").
 *
 * <p>{@code RolePrivilegeMatrixIT} confirms the catalogue-level shape of the grants (what {@code
 * has_table_privilege} reports); this class is the same distinction {@code
 * IdempotencyRowSecurityIT} and {@code IdempotencyKeyPrivilegeIT} already draw for
 * {@code shared_idempotency_key}, applied here: a catalogue assertion proves the policy and the
 * grants exist, only a real two-institution read and a real rejected statement prove they work.
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
            assertRejected(connection,
                    "select count(*) from identity_staff_account where institution_id = ?",
                    institutionId);
            connection.rollback();

            setInstitutionContext(connection, institutionId);
            assertRejected(connection, """
                    insert into identity_staff_account (institution_id, id, email, password_hash)
                    values (?, ?, ?, ?)
                    """, institutionId, UUID.randomUUID(), email, PLACEHOLDER_PASSWORD_HASH);
            connection.rollback();

            setInstitutionContext(connection, institutionId);
            assertRejected(connection, """
                    update identity_staff_account set password_hash = ?
                    where institution_id = ? and email = ?
                    """, PLACEHOLDER_PASSWORD_HASH, institutionId, email);
            connection.rollback();

            setInstitutionContext(connection, institutionId);
            assertRejected(connection,
                    "delete from identity_staff_account where institution_id = ? and email = ?",
                    institutionId, email);
            connection.rollback();
        }
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

    private static void assertRejected(Connection connection, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            assertThatThrownBy(statement::execute)
                    .as("confia_portal_app must have no privilege on identity_staff_account")
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
