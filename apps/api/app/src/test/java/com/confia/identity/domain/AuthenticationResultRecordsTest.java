package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.domain.AuthenticationResult.Authenticated;
import com.confia.identity.domain.AuthenticationResult.Rejected;
import com.confia.identity.domain.AuthenticationResult.RejectionReason;
import com.confia.kernel.InstitutionId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Construction guards of {@link AuthenticationResult}'s two nested outcomes (ADR-0019, point 6),
 * and that the sealed interface really only permits those two. This is a domain-level unit test of
 * the record's own null checks; the reflection-based proof that neither outcome carries an
 * authorization-scope field belongs to the application-level use case test
 * (design.md §7.1, {@code AuthenticationResultTest}, PR C3a) once there is a caller to exercise.
 */
class AuthenticationResultRecordsTest {

    private static final StaffAccountId USER_ID = new StaffAccountId(UUID.randomUUID());
    private static final InstitutionId INSTITUTION_ID = new InstitutionId(UUID.randomUUID());

    @Test
    void authenticatedExposesItsTwoIdentifiers() {
        Authenticated authenticated = new Authenticated(USER_ID, INSTITUTION_ID);

        assertThat(authenticated.userId()).isEqualTo(USER_ID);
        assertThat(authenticated.institutionId()).isEqualTo(INSTITUTION_ID);
        assertThat((AuthenticationResult) authenticated).isInstanceOf(AuthenticationResult.class);
    }

    @Test
    void authenticatedRejectsANullUserId() {
        assertThatThrownBy(() -> new Authenticated(null, INSTITUTION_ID))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void authenticatedRejectsANullInstitutionId() {
        assertThatThrownBy(() -> new Authenticated(USER_ID, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectedExposesItsReason() {
        Rejected rejected = new Rejected(RejectionReason.INVALID_PASSWORD);

        assertThat(rejected.reason()).isEqualTo(RejectionReason.INVALID_PASSWORD);
    }

    @Test
    void rejectedRejectsANullReason() {
        assertThatThrownBy(() -> new Rejected(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void exactlyTwoRejectionReasonsExistToday() {
        assertThat(RejectionReason.values()).containsExactlyInAnyOrder(
                RejectionReason.INVALID_PASSWORD, RejectionReason.ACCOUNT_NOT_FOUND);
    }

    @Test
    void rejectionReasonRoundTripsThroughItsName() {
        assertThat(RejectionReason.valueOf("ACCOUNT_NOT_FOUND"))
                .isEqualTo(RejectionReason.ACCOUNT_NOT_FOUND);
    }
}
