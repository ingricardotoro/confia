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

    @Test
    void exposesItsThreeComponents() {
        StaffAccount account = new StaffAccount(ID, INSTITUTION_ID, IDENTIFIER);

        assertThat(account.id()).isEqualTo(ID);
        assertThat(account.institutionId()).isEqualTo(INSTITUTION_ID);
        assertThat(account.identifier()).isEqualTo(IDENTIFIER);
    }

    @Test
    void rejectsANullId() {
        assertThatThrownBy(() -> new StaffAccount(null, INSTITUTION_ID, IDENTIFIER))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANullInstitutionId() {
        assertThatThrownBy(() -> new StaffAccount(ID, null, IDENTIFIER))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsANullIdentifier() {
        assertThatThrownBy(() -> new StaffAccount(ID, INSTITUTION_ID, null))
                .isInstanceOf(NullPointerException.class);
    }
}
