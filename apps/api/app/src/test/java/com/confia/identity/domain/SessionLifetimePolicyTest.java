package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link SessionLifetimePolicy} (session-tokens-and-web-layer design.md decision 8; scenario I87):
 * the single source of the 12-hour family, the 8-hour refresh token clipped to the family's end and
 * the 30-minute inactivity. Every instant is given; the policy has no clock of its own.
 */
class SessionLifetimePolicyTest {

    private static final Instant EIGHT = Instant.parse("2026-10-12T08:00:00Z");

    private final SessionLifetimePolicy policy = new SessionLifetimePolicy();

    @Test
    void theConstantsAreExactlyThoseOfTheSpecification() {
        assertThat(SessionLifetimePolicy.ABSOLUTE_LIFETIME).isEqualTo(Duration.ofHours(12));
        assertThat(SessionLifetimePolicy.REFRESH_LIFETIME).isEqualTo(Duration.ofHours(8));
        assertThat(SessionLifetimePolicy.IDLE_TIMEOUT).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void aFamilyEndsTwelveHoursAfterItIsCreated() {
        assertThat(policy.absoluteExpiresAt(EIGHT)).isEqualTo(Instant.parse("2026-10-12T20:00:00Z"));
    }

    @Test
    void aFirstRefreshTokenLivesEightHours() {
        Instant absolute = policy.absoluteExpiresAt(EIGHT);

        assertThat(policy.refreshExpiresAt(EIGHT, absolute))
                .isEqualTo(Instant.parse("2026-10-12T16:00:00Z"));
    }

    @Test
    void aRefreshTokenIsClippedToTheEndOfTheFamilyAndNeverOutlivesIt() {
        Instant absolute = Instant.parse("2026-10-12T20:00:00Z");

        assertThat(policy.refreshExpiresAt(Instant.parse("2026-10-12T12:00:00Z"), absolute))
                .as("exactly eight hours before the end, both rules give the same instant")
                .isEqualTo(absolute);
        assertThat(policy.refreshExpiresAt(Instant.parse("2026-10-12T12:00:01Z"), absolute))
                .as("one second later the family's end is the earlier of the two").isEqualTo(absolute);
        assertThat(policy.refreshExpiresAt(Instant.parse("2026-10-12T11:59:59Z"), absolute))
                .as("one second earlier eight hours is the earlier of the two")
                .isEqualTo(Instant.parse("2026-10-12T19:59:59Z"));
    }

    @Test
    void aRefreshTokenCannotBeIssuedAfterTheEndOfItsFamily() {
        Instant absolute = Instant.parse("2026-10-12T20:00:00Z");

        assertThatThrownBy(() -> policy.refreshExpiresAt(absolute, absolute))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSessionIsIdleExactlyThirtyMinutesAfterItsLastUse() {
        assertThat(policy.isIdle(EIGHT, Instant.parse("2026-10-12T08:29:59Z"))).isFalse();
        assertThat(policy.isIdle(EIGHT, Instant.parse("2026-10-12T08:30:00Z"))).isTrue();
        assertThat(policy.isIdle(EIGHT, Instant.parse("2026-10-12T08:30:01Z"))).isTrue();
        assertThat(policy.isIdle(EIGHT, EIGHT)).isFalse();
    }

    @Test
    void theGraceWindowIsTenSecondsAndEndsExactlyAtTheTenth() {
        assertThat(SessionLifetimePolicy.GRACE_WINDOW).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.isWithinGraceWindow(EIGHT, EIGHT)).isTrue();
        assertThat(policy.isWithinGraceWindow(EIGHT, Instant.parse("2026-10-12T08:00:09.999Z")))
                .isTrue();
        assertThat(policy.isWithinGraceWindow(EIGHT, Instant.parse("2026-10-12T08:00:10Z")))
                .isFalse();
        assertThat(policy.isWithinGraceWindow(EIGHT, Instant.parse("2026-10-12T08:01:00Z")))
                .isFalse();
    }

    /**
     * The border that empties an encrypted successor (design.md decision 10, step 5) is the same one
     * that closes the window: a token consumed exactly ten seconds ago is no longer replayable, so its
     * successor is emptied; one consumed a millisecond later than that is still replayable and is kept.
     */
    @Test
    void theLatestConsumptionWhoseWindowIsClosedIsExactlyTenSecondsBeforeNow() {
        Instant now = Instant.parse("2026-10-12T08:00:30Z");
        Instant cutoff = policy.latestConsumptionOutsideGraceWindow(now);

        assertThat(cutoff).isEqualTo(Instant.parse("2026-10-12T08:00:20Z"));
        assertThat(policy.isWithinGraceWindow(cutoff, now)).as("the border itself is outside").isFalse();
        assertThat(policy.isWithinGraceWindow(cutoff.plusMillis(1), now))
                .as("a millisecond later is still inside").isTrue();
    }

    @Test
    void aFamilyIsAbsolutelyExpiredAtItsEndAndNotBefore() {
        Instant end = Instant.parse("2026-10-12T20:00:00Z");

        assertThat(policy.isAbsolutelyExpired(end, Instant.parse("2026-10-12T19:59:59Z"))).isFalse();
        assertThat(policy.isAbsolutelyExpired(end, end)).isTrue();
        assertThat(policy.isAbsolutelyExpired(end, Instant.parse("2026-10-12T20:00:01Z"))).isTrue();
    }

    // --- Whether a family is live (decision 7; scenarios I115 and I116) ---

    private static final StaffAccountId OWNER = new StaffAccountId(UUID.randomUUID());
    private static final Instant NOON = Instant.parse("2026-10-12T12:00:00Z");

    private static RefreshTokenFamilyState family(StaffAccountId account, Instant lastUsedAt,
            Instant absoluteEnd, Instant revokedAt) {
        return new RefreshTokenFamilyState(UUID.randomUUID(), account, false, lastUsedAt,
                absoluteEnd, revokedAt);
    }

    @Test
    void aFamilyOfTheOwnerThatIsNotRevokedNotEndedAndNotIdleIsLive() {
        RefreshTokenFamilyState live = family(OWNER, NOON.minus(Duration.ofMinutes(29)),
                NOON.plusSeconds(1), null);

        assertThat(policy.isLive(live, OWNER, NOON)).isTrue();
    }

    @Test
    void aRevokedFamilyIsNeverLiveWhateverItsWindow() {
        RefreshTokenFamilyState revoked = family(OWNER, NOON, NOON.plus(Duration.ofHours(1)),
                NOON.minusSeconds(60));

        assertThat(policy.isLive(revoked, OWNER, NOON)).isFalse();
    }

    @Test
    void aFamilyIsNotLiveAtItsAbsoluteEndNorAtThirtyMinutesWithoutUse() {
        assertThat(policy.isLive(family(OWNER, NOON, NOON, null), OWNER, NOON))
                .as("the absolute end itself").isFalse();
        assertThat(policy.isLive(family(OWNER, NOON.minus(Duration.ofMinutes(30)),
                NOON.plus(Duration.ofHours(1)), null), OWNER, NOON))
                .as("exactly thirty minutes without use").isFalse();
        assertThat(policy.isLive(family(OWNER, NOON.minus(Duration.ofMinutes(30)).plusMillis(1),
                NOON.plus(Duration.ofHours(1)), null), OWNER, NOON))
                .as("a millisecond short of that").isTrue();
    }

    @Test
    void aFamilyOfAnotherAccountIsNotLiveForTheActor() {
        RefreshTokenFamilyState other = family(new StaffAccountId(UUID.randomUUID()), NOON,
                NOON.plus(Duration.ofHours(1)), null);

        assertThat(policy.isLive(other, OWNER, NOON)).isFalse();
    }
}
