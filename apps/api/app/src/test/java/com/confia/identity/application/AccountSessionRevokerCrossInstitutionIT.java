package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.JooqAccountSessionRevoker;
import com.confia.kernel.InstitutionId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

/**
 * Follow-up F1b of the archived session-tokens change (session-endpoints-cookie-and-csrf specs/identity
 * spec.md, scenario I208): revoking every live family of an account in one institution does not touch
 * another institution, with the same account or with another one. Real PostgreSQL, the least-privilege
 * role {@code confia_admin_app} and two institutions with their own fixtures.
 *
 * <p>Institution A holds one account with two live families. Institution B holds the <b>same account
 * identifier</b> (the key of an account is the pair institution and identifier, so the same identifier
 * can exist in both) with two live families, and another account with two more. One family of each
 * institution was rotated, so each holds an encrypted successor that a revocation would empty.
 *
 * <p>Row-level security already hides B from a transaction opened for A; the last test keeps the
 * institution argument of the revoker from being redundant with it by naming B in a transaction opened
 * for A.
 */
class AccountSessionRevokerCrossInstitutionIT extends RevocationIntegrationTest {

    private static final String NOW = "2026-10-15T15:20:00Z";

    private final InstitutionId institutionA = institutionId;
    private final InstitutionId institutionB = new InstitutionId(UUID.randomUUID());
    private StaffAccountId sharedInA;
    private StaffAccountId sharedInB;
    private StaffAccountId otherInB;

    private void seedBothInstitutions() {
        StaffAccountId shared = new StaffAccountId(UUID.randomUUID());
        sharedInA = seedStaffAccountIn(institutionA, shared, ANA);
        sharedInB = seedStaffAccountIn(institutionB, shared, ANA);
        otherInB = seedStaffAccountIn(institutionB, new StaffAccountId(UUID.randomUUID()),
                "carlos.ramirez@colegio.edu.hn");
        issueRotatedAndPlain(institutionA, sharedInA);
        issueRotatedAndPlain(institutionB, sharedInB);
        issueRotatedAndPlain(institutionB, otherInB);
    }

    /** Two families of the account; the first was rotated once and holds an encrypted successor. */
    private void issueRotatedAndPlain(InstitutionId institution, StaffAccountId account) {
        IssuedSession rotated = issueIn(institution, "2026-10-15T15:00:00Z", account);
        issueIn(institution, "2026-10-15T15:01:00Z", account);
        rotateIn(institution, "2026-10-15T15:05:00Z", rotated.refreshToken().value());
    }

    @Test
    void revokingEveryFamilyOfAnAccountOfInstitutionALeavesInstitutionBUntouched() {
        seedBothInstitutions();

        int revoked = transactionRunner().execute(contextOf(institutionA),
                () -> new JooqAccountSessionRevoker(dsl).revokeAllLive(institutionA, sharedInA,
                        SessionRevocationReason.LOGOUT, Instant.parse(NOW)));

        assertThat(revoked).isEqualTo(2);
        assertThat(familiesIn(institutionA, sharedInA)).hasSize(2).allSatisfy(family ->
                assertThat(family.get("revoked_reason", String.class)).isEqualTo("logout"));
        assertThat(encryptedSuccessorsIn(institutionA, sharedInA)).isZero();
        assertInstitutionBIsUntouched();
    }

    @Test
    void closingEveryLiveSessionOfInstitutionAAlsoLeavesInstitutionBUntouched() {
        seedBothInstitutions();
        UUID current = familiesIn(institutionA, sharedInA).get(0).get("id", UUID.class);

        int ended = endAllSessions(NOW).execute(actorOf(institutionA, sharedInA, current),
                UUID.randomUUID().toString());

        assertThat(ended).isEqualTo(2);
        assertThat(familiesIn(institutionA, sharedInA)).allSatisfy(family ->
                assertThat(family.get("revoked_reason", String.class)).isEqualTo("logout"));
        assertThat(auditRowsIn(institutionA, ACTION_REVOKED)).hasSize(1);
        assertThat(auditRowsIn(institutionB, ACTION_REVOKED)).as("nothing was written for B")
                .isEmpty();
        assertInstitutionBIsUntouched();
    }

    @Test
    void theInstitutionArgumentOfTheRevokerIsNotLeftToRowLevelSecurityAlone() {
        seedBothInstitutions();

        int namingB = transactionRunner().execute(contextOf(institutionA),
                () -> new JooqAccountSessionRevoker(dsl).revokeAllLive(institutionB, sharedInA,
                        SessionRevocationReason.LOGOUT, Instant.parse(NOW)));
        int namingA = transactionRunner().execute(contextOf(institutionB),
                () -> new JooqAccountSessionRevoker(dsl).revokeAllLive(institutionA, sharedInB,
                        SessionRevocationReason.LOGOUT, Instant.parse(NOW)));

        assertThat(namingB).as("A's families are visible, but the argument names B").isZero();
        assertThat(namingA).as("B's families are visible, but the argument names A").isZero();
        assertThat(familiesIn(institutionA, sharedInA)).allSatisfy(family ->
                assertThat(family.get("revoked_at")).isNull());
        assertInstitutionBIsUntouched();
    }

    private void assertInstitutionBIsUntouched() {
        for (StaffAccountId account : List.of(sharedInB, otherInB)) {
            List<Record> families = familiesIn(institutionB, account);
            assertThat(families).hasSize(2).allSatisfy(family -> {
                assertThat(family.get("revoked_at")).isNull();
                assertThat(family.get("revoked_reason")).isNull();
            });
            assertThat(liveTokensIn(institutionB, account))
                    .as("each family of B keeps its open token").isEqualTo(2L);
            assertThat(encryptedSuccessorsIn(institutionB, account))
                    .as("the rotated family of B keeps its encrypted successor").isEqualTo(1L);
        }
    }
}
