package com.confia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The principal of a restricted route (session-endpoints-cookie-and-csrf design.md, decision 6): a
 * type of its own, bound in a {@link ScopedValue} of its own, so a consumer that reads {@link
 * AuthenticatedActor} can never mistake the holder of a restricted token for a session. It holds
 * identifiers and a purpose, and its {@code toString} leaves out the token identifier.
 */
class PendingSecondFactorTest {

    private static final UUID ACCOUNT = UUID.fromString("7d444840-9dc0-11d1-b245-5ffdce74fad2");
    private static final UUID INSTITUTION = UUID.fromString("c3a8e9a0-5b1e-4f7e-9d0e-2b6f0a1c4d11");
    private static final UUID TOKEN_ID = UUID.fromString("9b2e7c4a-1d3f-4a5b-86c7-d8e9f0a1b2c3");
    private static final Instant ISSUED = Instant.parse("2026-10-12T10:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-10-12T10:05:00Z");

    private static PendingSecondFactor pending(SecondFactorPurpose purpose) {
        return new PendingSecondFactor(ACCOUNT, INSTITUTION, TOKEN_ID, purpose, ISSUED, EXPIRES);
    }

    @Test
    void outsideARestrictedRouteThereIsNoPendingPrincipalAndNoSessionActor() {
        assertThat(PendingSecondFactor.current()).isEmpty();
        assertThat(PendingSecondFactor.CURRENT.isBound()).isFalse();
    }

    @Test
    void insideTheScopeOnlyThePendingPrincipalIsBoundNeverTheSessionActor() throws Exception {
        PendingSecondFactor bound = pending(SecondFactorPurpose.ENROLL);

        Optional<PendingSecondFactor> inside = ScopedValue.where(PendingSecondFactor.CURRENT, bound)
                .call(PendingSecondFactor::current);
        boolean actorInside = ScopedValue.where(PendingSecondFactor.CURRENT, bound)
                .call(() -> AuthenticatedActor.current().isPresent());

        assertThat(inside).containsSame(bound);
        assertThat(actorInside).isFalse();
        assertThat(PendingSecondFactor.current()).isEmpty();
        assertThat(PendingSecondFactor.CURRENT).isNotSameAs(AuthenticatedActor.CURRENT);
    }

    @Test
    void theSecurityContextIsThatOfAStaffActorWithTheAccountTheInstitutionAndTheGivenRequest() {
        SecurityContext context = pending(SecondFactorPurpose.VERIFY).securityContext("req-123");

        assertThat(context.actorId()).isEqualTo(ACCOUNT.toString());
        assertThat(context.actorKind()).isEqualTo("staff");
        assertThat(context.institutionId()).isEqualTo(INSTITUTION.toString());
        assertThat(context.requestId()).isEqualTo("req-123");
    }

    @Test
    void everyComponentIsRequired() {
        assertThatThrownBy(() -> new PendingSecondFactor(null, INSTITUTION, TOKEN_ID,
                SecondFactorPurpose.VERIFY, ISSUED, EXPIRES)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PendingSecondFactor(ACCOUNT, null, TOKEN_ID,
                SecondFactorPurpose.VERIFY, ISSUED, EXPIRES)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PendingSecondFactor(ACCOUNT, INSTITUTION, null,
                SecondFactorPurpose.VERIFY, ISSUED, EXPIRES)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PendingSecondFactor(ACCOUNT, INSTITUTION, TOKEN_ID, null, ISSUED,
                EXPIRES)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PendingSecondFactor(ACCOUNT, INSTITUTION, TOKEN_ID,
                SecondFactorPurpose.VERIFY, null, EXPIRES)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PendingSecondFactor(ACCOUNT, INSTITUTION, TOKEN_ID,
                SecondFactorPurpose.VERIFY, ISSUED, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void printingThePrincipalNeverShowsTheTokenIdentifier() {
        String printed = pending(SecondFactorPurpose.ENROLL).toString();

        assertThat(printed).doesNotContain(TOKEN_ID.toString()).contains(ACCOUNT.toString())
                .contains(INSTITUTION.toString()).contains("ENROLL");
    }

    @Test
    void thePurposesAreTheTwoOfTheRestrictedToken() {
        assertThat(SecondFactorPurpose.values()).containsExactly(SecondFactorPurpose.VERIFY,
                SecondFactorPurpose.ENROLL);
    }

    @Test
    void twoPrincipalsBuiltFromTheSameValuesAreEqualAndThePurposeDistinguishesThem() {
        assertThat(pending(SecondFactorPurpose.VERIFY)).isEqualTo(pending(SecondFactorPurpose.VERIFY))
                .hasSameHashCodeAs(pending(SecondFactorPurpose.VERIFY));
        assertThat(pending(SecondFactorPurpose.VERIFY)).isNotEqualTo(pending(SecondFactorPurpose.ENROLL));
    }
}
