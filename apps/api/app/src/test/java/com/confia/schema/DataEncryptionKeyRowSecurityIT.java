package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.InstitutionId;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Row-level security on {@code shared_data_encryption_key}, by institution
 * (column-encryption-and-mfa-totp design.md decisions 1 and 3; specs/build-integrity/spec.md,
 * requirement "Tablas nuevas de cifrado de columna y MFA...", scenarios "Una institución no lee la
 * llave de datos de otra" and "Sin contexto de institución, ninguna fila es visible").
 *
 * <p>{@code RolePrivilegeMatrixIT} confirms the catalogue-level shape of the grants; this class,
 * like {@code IdentityRowSecurityIT} before it, is the same distinction: a catalogue assertion
 * proves the policy and the grants exist, only a real two-institution read and a real absent
 * context prove they work.
 */
class DataEncryptionKeyRowSecurityIT extends CommittingPostgresIntegrationTest {

    /** A syntactically valid stand-in wrapped key: satisfies only the three-part base64 CHECK
     * ({@code V6}, decision 3, point 3); the real wrapping is exercised by {@code
     * ColumnEncryptionIT}. */
    private static final String PLACEHOLDER_WRAPPED_KEY = "aXY=:Y2lwaGVydGV4dA==:dGFn";

    @Test
    void oneInstitutionCannotReadAnotherInstitutionsDataEncryptionKey() {
        InstitutionId institutionA = new InstitutionId(UUID.randomUUID());
        InstitutionId institutionB = new InstitutionId(UUID.randomUUID());
        seedActiveKey(institutionA);
        seedActiveKey(institutionB);

        long visibleToB = countVisibleKeys(institutionB, institutionA);
        assertThat(visibleToB)
                .as("institution B must see none of institution A's data encryption keys, "
                        + "although the row physically exists: the row policy, not a query "
                        + "predicate, is what keeps them apart")
                .isZero();

        long ownRowVisibleToB = countVisibleKeys(institutionB, institutionB);
        assertThat(ownRowVisibleToB)
                .as("institution B must still see its own data encryption key: a policy that "
                        + "hid everything from everyone would satisfy the assertion above for "
                        + "the wrong reason")
                .isEqualTo(1L);
    }

    @Test
    void anAbsentInstitutionContextReturnsZeroRowsNotAPermissionError() {
        seedActiveKey(new InstitutionId(UUID.randomUUID()));

        long directCount = transactionRunner().execute(contextWithNoInstitution(),
                () -> dsl.fetchOne("select count(*) as c from shared_data_encryption_key")
                        .get("c", Number.class).longValue());

        assertThat(directCount)
                .as("with no app.institution_id set, the policy must deny by returning zero "
                        + "rows, never by raising a permission error")
                .isZero();
    }

    private void seedActiveKey(InstitutionId institutionId) {
        transactionRunner().execute(contextOf(institutionId), () -> {
            dsl.execute("""
                    insert into shared_data_encryption_key
                        (institution_id, id, status, wrapped_key)
                    values (?, ?, 'active', ?)
                    """, institutionId.value(), UUID.randomUUID(), PLACEHOLDER_WRAPPED_KEY);
            return null;
        });
    }

    private long countVisibleKeys(InstitutionId contextInstitutionId,
            InstitutionId queryInstitutionId) {
        return transactionRunner().execute(contextOf(contextInstitutionId),
                () -> dsl.fetchOne(
                                "select count(*) as c from shared_data_encryption_key "
                                        + "where institution_id = ?",
                                queryInstitutionId.value())
                        .get("c", Number.class).longValue());
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }

    /** No {@code institutionId} at all: {@code TransactionRunner} never sets {@code
     * app.institution_id}. */
    private static SecurityContext contextWithNoInstitution() {
        return new SecurityContext("", "system", "", UUID.randomUUID().toString());
    }
}
