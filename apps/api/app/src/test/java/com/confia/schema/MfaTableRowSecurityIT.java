package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Row-level security on the three identity MFA tables of {@code V6}, by institution (design.md
 * decisions 1 and 3; specs/build-integrity/spec.md, requirement "Tablas nuevas de cifrado de columna
 * y MFA, con {@code institution_id} y seguridad de fila forzada", whose scenarios say "cada una de
 * las cuatro" — not "la tabla del cambio"). {@code DataEncryptionKeyRowSecurityIT}, in this same
 * cut, covers the fourth.
 *
 * <p><b>Why the four proofs ship as one cut.</b> {@code RolePrivilegeMatrixIT} and {@code
 * MultiTenantSchemaIT} prove by catalogue that the four policies and grants exist, and a catalogue
 * assertion cannot prove that an identical policy string behaves identically on four different
 * tables. The same omission has now appeared in three changes running — change 6 on {@code
 * shared_idempotency_key}, part 1 on {@code identity_login_backoff}, and the pre-merge audit of this
 * change on these three — always as the isolation test written for the table the change is named
 * after, with the rest inheriting nothing but the assumption. Keeping the four in one reviewable unit
 * is what makes a missing one visible by counting instead of by remembering.
 *
 * <p>All three are parameterised because the property is identical and only the statement text
 * differs. Every SQL string below is a literal held by its own table descriptor: none is assembled
 * from a table name, so no query here is built by concatenation.
 */
class MfaTableRowSecurityIT extends CommittingPostgresIntegrationTest {

    /** A syntactically valid stand-in hash: satisfies the {@code $argon2id$} prefix CHECK only. */
    private static final String PLACEHOLDER_PASSWORD_HASH =
            "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g";

    /** One MFA table, with every statement this test runs against it, each one a literal. */
    private record MfaTable(String name, String insertOneRow, String countByInstitution,
            String countAll) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Stream<MfaTable> mfaTables() {
        return Stream.of(
                new MfaTable("identity_mfa_totp_credential",
                        """
                        insert into identity_mfa_totp_credential
                            (institution_id, account_id, encrypted_secret)
                        values (?, ?, 'v1:aXY=:Y2lwaGVydGV4dA==:dGFn')
                        """,
                        "select count(*) as c from identity_mfa_totp_credential "
                                + "where institution_id = ?",
                        "select count(*) as c from identity_mfa_totp_credential"),
                new MfaTable("identity_mfa_recovery_code",
                        """
                        insert into identity_mfa_recovery_code
                            (institution_id, account_id, id, code_hash)
                        values (?, ?, gen_random_uuid(), '$argon2id$v=19$m=19456,t=3,p=1$c2FsdA$aGFzaA')
                        """,
                        "select count(*) as c from identity_mfa_recovery_code "
                                + "where institution_id = ?",
                        "select count(*) as c from identity_mfa_recovery_code"),
                new MfaTable("identity_mfa_totp_backoff",
                        """
                        insert into identity_mfa_totp_backoff
                            (institution_id, account_id, last_attempt_at)
                        values (?, ?, clock_timestamp())
                        """,
                        "select count(*) as c from identity_mfa_totp_backoff "
                                + "where institution_id = ?",
                        "select count(*) as c from identity_mfa_totp_backoff"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mfaTables")
    void oneInstitutionCannotReadAnotherInstitutionsRow(MfaTable table) {
        UUID institutionA = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();
        seedOneRow(table, institutionA);
        seedOneRow(table, institutionB);

        assertThat(countVisible(table, institutionB, institutionA))
                .as("institution B must see none of institution A's rows in %s, although the row "
                        + "physically exists: the row policy, not a query predicate, is what keeps "
                        + "them apart", table.name())
                .isZero();

        assertThat(countVisible(table, institutionB, institutionB))
                .as("institution B must still see its own row in %s: a policy that hid everything "
                        + "from everyone would satisfy the assertion above for the wrong reason",
                        table.name())
                .isEqualTo(1L);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mfaTables")
    void anAbsentInstitutionContextReturnsZeroRowsNotAPermissionError(MfaTable table) {
        seedOneRow(table, UUID.randomUUID());

        long directCount = transactionRunner().execute(contextWithNoInstitution(),
                () -> dsl.fetchOne(table.countAll()).get("c", Number.class).longValue());

        assertThat(directCount)
                .as("with no app.institution_id set, the policy on %s must deny by returning zero "
                        + "rows, never by raising a permission error", table.name())
                .isZero();
    }

    /**
     * Seeds the account the foreign key of all three tables demands, then one row of {@code table}
     * for it. {@code gen_random_uuid()} is core PostgreSQL since 13 and needs no {@code pgcrypto},
     * which this schema's owner cannot create anyway (sonda S6, exploration.md).
     */
    private void seedOneRow(MfaTable table, UUID institutionId) {
        UUID accountId = UUID.randomUUID();
        transactionRunner().execute(contextOf(institutionId), () -> {
            dsl.execute("""
                    insert into identity_staff_account
                        (institution_id, id, email, password_hash, mfa_required)
                    values (?, ?, ?, ?, true)
                    """, institutionId, accountId, "mfa." + accountId + "@colegio.edu.hn",
                    PLACEHOLDER_PASSWORD_HASH);
            dsl.execute(table.insertOneRow(), institutionId, accountId);
            return null;
        });
    }

    private long countVisible(MfaTable table, UUID contextInstitutionId, UUID queryInstitutionId) {
        return transactionRunner().execute(contextOf(contextInstitutionId),
                () -> dsl.fetchOne(table.countByInstitution(), queryInstitutionId)
                        .get("c", Number.class).longValue());
    }

    private static SecurityContext contextOf(UUID institutionId) {
        return new SecurityContext("", "system", institutionId.toString(),
                UUID.randomUUID().toString());
    }

    /** No {@code institutionId} at all: {@code TransactionRunner} never sets {@code
     * app.institution_id}. */
    private static SecurityContext contextWithNoInstitution() {
        return new SecurityContext("", "system", "", UUID.randomUUID().toString());
    }
}
