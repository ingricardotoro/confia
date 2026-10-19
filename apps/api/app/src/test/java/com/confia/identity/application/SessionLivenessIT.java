package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.domain.RefreshTokenFamilyState;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.JooqRefreshTokenFamilyRepository;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;
import org.junit.jupiter.api.Test;

/**
 * Whether a session is still valid, against a real PostgreSQL (session-tokens-and-web-layer design.md
 * decision 7; scenarios I115, I116 and I146): the adapter of the port, with the real family adapter
 * and the real row-level security of the administrative role. Families are seeded with SQL, because a
 * window a real issuance could not reach (an absolute end at 20:00 with the last use at 19:50) is the
 * point.
 *
 * <p>It lives beside the other session integration tests, in {@code identity.application}, because
 * the adapter does: the set-up of {@link RotationIntegrationTest} is package-private.
 */
class SessionLivenessIT extends RotationIntegrationTest {

    private static final String EIGHT = "2026-10-12T08:00:00Z";
    private static final String TWENTY = "2026-10-12T20:00:00Z";

    private SessionLiveness liveness(String instant) {
        return new SessionLiveness(transactionRunner(), new JooqRefreshTokenFamilyRepository(dsl),
                fixedClock(instant));
    }

    private AuthenticatedActor actor(StaffAccountId account, UUID family) {
        return actorOf(account, institutionId, family);
    }

    private static AuthenticatedActor actorOf(StaffAccountId account, InstitutionId institution,
            UUID family) {
        return new AuthenticatedActor(account.value(), institution.value(), family, UUID.randomUUID(),
                Set.of(AuthenticationMethod.PASSWORD), Instant.parse(EIGHT),
                Instant.parse(EIGHT).plusSeconds(600));
    }

    // --- I115 and I116 ---

    @Test
    void aLiveFamilyIsActive() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);

        assertThat(liveness("2026-10-12T08:29:59Z").isActive(actor(ana, family))).isTrue();
    }

    @Test
    void aRevokedFamilyIsNotActiveWhateverItsWindow() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID revoked = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, "2026-10-12T08:10:00Z",
                SessionRevocationReason.PASSWORD_CHANGE.code());

        assertThat(liveness("2026-10-12T08:11:00Z").isActive(actor(ana, revoked))).isFalse();
    }

    @Test
    void aFamilyIsNotActiveAtItsAbsoluteEndAndIsOneSecondBefore() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, "2026-10-12T19:50:00Z", TWENTY, null, null);

        assertThat(liveness("2026-10-12T19:59:59Z").isActive(actor(ana, family)))
                .as("one second before the end").isTrue();
        assertThat(liveness(TWENTY).isActive(actor(ana, family))).as("the end itself").isFalse();
    }

    @Test
    void aFamilyIsNotActiveAfterExactlyThirtyMinutesWithoutUse() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);

        assertThat(liveness("2026-10-12T08:29:59Z").isActive(actor(ana, family))).isTrue();
        assertThat(liveness("2026-10-12T08:30:00Z").isActive(actor(ana, family)))
                .as("exactly thirty minutes").isFalse();
    }

    @Test
    void anUnknownFamilyIsNotActive() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);

        assertThat(liveness("2026-10-12T08:10:00Z").isActive(actor(ana, UUID.randomUUID())))
                .isFalse();
    }

    @Test
    void aFamilyOfAnotherInstitutionIsNotActiveUnderTheContextOfTheCaller() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        InstitutionId other = new InstitutionId(UUID.randomUUID());

        assertThat(liveness("2026-10-12T08:10:00Z").isActive(actorOf(ana, other, family)))
                .isFalse();
        assertThat(liveness("2026-10-12T08:10:00Z").isActive(actor(ana, family)))
                .as("non-vacuous: under its own institution the same family is active").isTrue();
    }

    @Test
    void aLiveFamilyOfAnotherAccountOfTheSameInstitutionIsNotActive() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        StaffAccountId luis = seedStaffAccount("luis.perez@colegio.edu.hn", CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);

        assertThat(liveness("2026-10-12T08:10:00Z").isActive(actor(luis, family))).isFalse();
        assertThat(liveness("2026-10-12T08:10:00Z").isActive(actor(ana, family))).isTrue();
    }

    // --- Reading only (decision 7: it does not write, audit or move last_used_at) ---

    @Test
    void askingNeverWritesAuditsOrMovesTheLastUse() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        Record before = familyRow(family);
        int auditBefore = auditRows().size();

        liveness("2026-10-12T08:20:00Z").isActive(actor(ana, family));
        liveness("2026-10-12T08:20:00Z").isActive(actor(ana, UUID.randomUUID()));

        assertThat(familyRow(family)).as("the family row is exactly as it was").isEqualTo(before);
        assertThat(instantOf(familyRow(family), "last_used_at")).isEqualTo(Instant.parse(EIGHT));
        assertThat(auditRows()).as("no audit entry").hasSize(auditBefore);
    }

    // --- I146 ---

    @Test
    void aDatabaseFailureReachesTheCallerInsteadOfAnAnswer() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        DataAccessException failure = new DataAccessException("the database is not there");
        SessionLiveness failing = new SessionLiveness(transactionRunner(),
                new FailingFamilies(failure), fixedClock("2026-10-12T08:10:00Z"));

        assertThatThrownBy(() -> failing.isActive(actor(ana, family))).isSameAs(failure);
    }

    /** A family repository whose read fails the way a broken connection does. */
    private static final class FailingFamilies implements RefreshTokenFamilyRepository {

        private final RuntimeException failure;

        FailingFamilies(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public void insert(InstitutionId institutionId,
                com.confia.identity.domain.RefreshTokenFamily family) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RefreshTokenFamilyState> lockById(InstitutionId institutionId,
                UUID familyId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<RefreshTokenFamilyState> readById(InstitutionId institutionId,
                UUID familyId) {
            throw failure;
        }

        @Override
        public void recordUse(InstitutionId institutionId, UUID familyId, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean revokeForReuse(InstitutionId institutionId, UUID familyId, Instant now) {
            throw new UnsupportedOperationException();
        }
    }
}
