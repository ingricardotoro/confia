package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
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

    @Test
    void aFamilyIsAbsolutelyExpiredAtItsEndAndNotBefore() {
        Instant end = Instant.parse("2026-10-12T20:00:00Z");

        assertThat(policy.isAbsolutelyExpired(end, Instant.parse("2026-10-12T19:59:59Z"))).isFalse();
        assertThat(policy.isAbsolutelyExpired(end, end)).isTrue();
        assertThat(policy.isAbsolutelyExpired(end, Instant.parse("2026-10-12T20:00:01Z"))).isTrue();
    }
}
