package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.InstitutionId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link StaffAccount} construction guards (ADR-0019, point 6): each field is a programming-error
 * null check, never a {@code DomainException} subclass.
 */
class StaffAccountTest {

    private static final StaffAccountId ID = new StaffAccountId(UUID.randomUUID());
    private static final InstitutionId INSTITUTION_ID = new InstitutionId(UUID.randomUUID());
    private static final LoginIdentifier IDENTIFIER = LoginIdentifier.of("maria.lopez@colegio.edu.hn");
    private static final StoredPasswordHash PASSWORD_HASH =
            new StoredPasswordHash("$argon2id$v=19$m=19456,t=3,p=1$c2FsdA$dGFn");

    @Test
    void exposesItsFourComponents() {
        StaffAccount account = new StaffAccount(ID, INSTITUTION_ID, IDENTIFIER, PASSWORD_HASH);

        assertThat(account.id()).isEqualTo(ID);
        assertThat(account.institutionId()).isEqualTo(INSTITUTION_ID);
        assertThat(account.identifier()).isEqualTo(IDENTIFIER);
        assertThat(account.passwordHash()).isEqualTo(PASSWORD_HASH);
    }

    @Test
    void rejectsANullId() {
        assertThatThrownBy(
                () -> new StaffAccount(null, INSTITUTION_ID, IDENTIFIER, PASSWORD_HASH))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANullInstitutionId() {
        assertThatThrownBy(() -> new StaffAccount(ID, null, IDENTIFIER, PASSWORD_HASH))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANullIdentifier() {
        assertThatThrownBy(() -> new StaffAccount(ID, INSTITUTION_ID, null, PASSWORD_HASH))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANullPasswordHash() {
        assertThatThrownBy(() -> new StaffAccount(ID, INSTITUTION_ID, IDENTIFIER, null))
                .isInstanceOf(NullPointerException.class);
    }
}
