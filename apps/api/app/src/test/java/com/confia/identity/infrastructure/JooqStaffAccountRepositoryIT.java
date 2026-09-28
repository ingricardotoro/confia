package com.confia.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.StaffAccount;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StoredPasswordHash;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link JooqStaffAccountRepository} against a real PostgreSQL (design.md §10, sonda S4; §11 paso
 * 16). Row-level security on {@code identity_staff_account} (design.md decision 4) is what actually
 * scopes {@link JooqStaffAccountRepository#findBy} to one institution: this class proves the
 * adapter's own query cooperates with that policy rather than fighting it.
 */
class JooqStaffAccountRepositoryIT extends CommittingPostgresIntegrationTest {

    /** Satisfies only the {@code $argon2id$} prefix CHECK — the real codec is exercised elsewhere. */
    private static final String PLACEHOLDER_PASSWORD_HASH =
            "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g";

    private JooqStaffAccountRepository repository() {
        return new JooqStaffAccountRepository(dsl);
    }

    @Test
    void findsTheAccountOfItsOwnInstitutionByNormalizedIdentifier() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        LoginIdentifier identifier = LoginIdentifier.of("Maria.Lopez@Colegio.edu.hn");
        insertStaffAccount(institutionId, accountId, identifier);

        Optional<StaffAccount> found = transactionRunner().execute(contextOf(institutionId),
                () -> repository().findBy(institutionId, identifier));

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(accountId);
        assertThat(found.get().identifier()).isEqualTo(identifier);
        assertThat(found.get().passwordHash())
                .isEqualTo(new StoredPasswordHash(PLACEHOLDER_PASSWORD_HASH));
    }

    @Test
    void findsNothingForAnotherInstitutionsAccount() {
        InstitutionId institutionA = new InstitutionId(UUID.randomUUID());
        InstitutionId institutionB = new InstitutionId(UUID.randomUUID());
        LoginIdentifier identifier = LoginIdentifier.of("juan.perez@colegio.edu.hn");
        insertStaffAccount(institutionA, new StaffAccountId(UUID.randomUUID()), identifier);

        Optional<StaffAccount> found = transactionRunner().execute(contextOf(institutionB),
                () -> repository().findBy(institutionB, identifier));

        assertThat(found)
                .as("institution B's own session context must never see institution A's row, "
                        + "row-level security first")
                .isEmpty();
    }

    @Test
    void findsNothingForAnUnregisteredIdentifier() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        LoginIdentifier identifier = LoginIdentifier.of("nadie.registrado@colegio.edu.hn");

        Optional<StaffAccount> found = transactionRunner().execute(contextOf(institutionId),
                () -> repository().findBy(institutionId, identifier));

        assertThat(found).isEmpty();
    }

    private void insertStaffAccount(InstitutionId institutionId, StaffAccountId accountId,
            LoginIdentifier identifier) {
        transactionRunner().execute(contextOf(institutionId), () -> {
            dsl.execute("""
                    insert into identity_staff_account (institution_id, id, email, password_hash)
                    values (?, ?, ?, ?)
                    """, institutionId.value(), accountId.value(), identifier.value(),
                    PLACEHOLDER_PASSWORD_HASH);
            return null;
        });
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}
