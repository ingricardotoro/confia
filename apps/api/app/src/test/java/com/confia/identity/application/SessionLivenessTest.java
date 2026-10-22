package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.domain.RefreshTokenFamily;
import com.confia.identity.domain.RefreshTokenFamilyState;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link SessionLiveness} with the family adapter replaced by a double (testing playbook, section 1;
 * session-tokens-and-web-layer design.md decision 7): which family it asks for, the instant it
 * decides at, that a failure of the read is not turned into an answer, and that the read never goes
 * through the locking operation of the adapter. The real rows and the real row-level security are
 * {@code SessionLivenessIT}.
 */
class SessionLivenessTest {

    private static final Instant NOON = Instant.parse("2026-10-12T12:00:00Z");
    private static final StaffAccountId ANA = new StaffAccountId(UUID.randomUUID());
    private static final UUID INSTITUTION = UUID.randomUUID();
    private static final UUID FAMILY = UUID.randomUUID();

    private final List<String> transaction = new ArrayList<>();
    private final FakeFamilies families = new FakeFamilies();
    private final SessionLiveness liveness = new SessionLiveness(
            JdbcStubTransactions.runner(transaction), families, Clock.fixed(NOON, ZoneOffset.UTC));

    private static AuthenticatedActor actor(StaffAccountId account) {
        return new AuthenticatedActor(account.value(), INSTITUTION, FAMILY, UUID.randomUUID(),
                Set.of(AuthenticationMethod.PASSWORD), NOON, NOON.plusSeconds(600));
    }

    private static RefreshTokenFamilyState state(StaffAccountId account, Instant lastUsedAt,
            Instant revokedAt) {
        return new RefreshTokenFamilyState(FAMILY, account, false, lastUsedAt,
                NOON.plus(Duration.ofHours(6)), revokedAt);
    }

    @Test
    void aLiveFamilyIsActiveAndTheLookupNamesTheInstitutionAndTheSessionOfTheActor() {
        families.state = state(ANA, NOON.minus(Duration.ofMinutes(5)), null);

        assertThat(liveness.isActive(actor(ANA))).isTrue();
        assertThat(families.asked).containsExactly(new InstitutionId(INSTITUTION) + "/" + FAMILY);
        assertThat(families.lockCalls).as("it only peeks, it never takes the row lock").isZero();
    }

    @Test
    void anUnknownRevokedOrForeignFamilyIsNotActive() {
        families.state = null;
        assertThat(liveness.isActive(actor(ANA))).as("unknown").isFalse();

        families.state = state(ANA, NOON, NOON.minusSeconds(60));
        assertThat(liveness.isActive(actor(ANA))).as("revoked").isFalse();

        families.state = state(new StaffAccountId(UUID.randomUUID()), NOON, null);
        assertThat(liveness.isActive(actor(ANA))).as("of another account").isFalse();
    }

    @Test
    void itDecidesAtTheInstantOfItsClock() {
        families.state = state(ANA, NOON.minus(Duration.ofMinutes(30)), null);
        assertThat(liveness.isActive(actor(ANA))).as("thirty minutes idle").isFalse();

        families.state = state(ANA, NOON.minus(Duration.ofMinutes(30)).plusSeconds(1), null);
        assertThat(liveness.isActive(actor(ANA))).as("one second short of it").isTrue();
    }

    @Test
    void aFailureOfTheReadReachesTheCallerAndRollsTheTransactionBack() {
        IllegalStateException failure = new IllegalStateException("the database failed");
        families.failure = failure;

        assertThatThrownBy(() -> liveness.isActive(actor(ANA))).isSameAs(failure);
        assertThat(transaction).containsExactly("rollback");
    }

    private static final class FakeFamilies implements RefreshTokenFamilyRepository {

        final List<String> asked = new ArrayList<>();
        int lockCalls;
        RefreshTokenFamilyState state;
        RuntimeException failure;

        @Override
        public Optional<RefreshTokenFamilyState> readById(InstitutionId institutionId,
                UUID familyId) {
            asked.add(institutionId + "/" + familyId);
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(state);
        }

        @Override
        public Optional<RefreshTokenFamilyState> lockById(InstitutionId institutionId,
                UUID familyId) {
            lockCalls++;
            return Optional.ofNullable(state);
        }

        @Override
        public void insert(InstitutionId institutionId, RefreshTokenFamily family) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordUse(InstitutionId institutionId, UUID familyId, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean revokeForReuse(InstitutionId institutionId, UUID familyId, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean revoke(InstitutionId institutionId, UUID familyId,
                SessionRevocationReason reason, Instant now) {
            throw new UnsupportedOperationException();
        }
    }
}
