package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.statistics.Statistics;

/**
 * Properties of {@link RotationDecision} (session-tokens-and-web-layer design.md decisions 10 and
 * 11): the grace replay is only ever returned inside its ten seconds, only when all four of its
 * conditions hold, and a revoked family wins over every other input.
 */
class RotationDecisionPropertiesTest {

    private static final Instant CONSUMED_AT = Instant.parse("2026-10-12T08:00:00Z");
    private static final StaffAccountId ACCOUNT = new StaffAccountId(UUID.randomUUID());
    private static final SessionLifetimePolicy POLICY = new SessionLifetimePolicy();
    private static final UserAgentFingerprint ONE = UserAgentFingerprint.of("agente-uno");
    private static final UserAgentFingerprint TWO = UserAgentFingerprint.of("agente-dos");

    /** The family is kept live over the whole range of instants the properties draw. */
    private static final RefreshTokenFamilyState LIVE = new RefreshTokenFamilyState(
            UUID.randomUUID(), ACCOUNT, false, CONSUMED_AT.minusSeconds(1),
            CONSUMED_AT.plus(Duration.ofHours(11)), null);

    @Property(tries = 1000)
    void neverAGraceReplayTenSecondsOrMoreAfterTheConsumption(
            @ForAll @LongRange(min = 10_000, max = 1_500_000) long millisAfter,
            @ForAll boolean sameDevice, @ForAll boolean ciphertext,
            @ForAll boolean successorConsumed) {
        RefreshTokenState token = consumed(sameDevice ? ONE : TWO, ciphertext, successorConsumed);

        RotationDecision decision = RotationDecision.decide(LIVE, token, ONE,
                CONSUMED_AT.plusMillis(millisAfter), POLICY);

        assertThat(decision).isNotEqualTo(RotationDecision.GRACE_REPLAY);
        assertThat(decision).isEqualTo(RotationDecision.REUSE_DETECTED);
    }

    @Property(tries = 1000)
    void aGraceReplayIsReturnedExactlyWhenAllFourConditionsHold(
            @ForAll @LongRange(min = 0, max = 30_000) long millisAfter,
            @ForAll boolean sameDevice, @ForAll boolean ciphertext,
            @ForAll boolean successorConsumed) {
        RefreshTokenState token = consumed(sameDevice ? ONE : TWO, ciphertext, successorConsumed);
        boolean expected = millisAfter < 10_000 && sameDevice && ciphertext && !successorConsumed;

        RotationDecision decision = RotationDecision.decide(LIVE, token, ONE,
                CONSUMED_AT.plusMillis(millisAfter), POLICY);

        Statistics.collect(expected ? "grace" : "reuse");
        assertThat(decision).isEqualTo(expected ? RotationDecision.GRACE_REPLAY
                : RotationDecision.REUSE_DETECTED);
    }

    @Property(tries = 500)
    void aRevokedFamilyRejectsWhateverTheTokenAndTheInstant(
            @ForAll("tokens") RefreshTokenState token,
            @ForAll @LongRange(min = -100_000, max = 100_000_000) long millisAfter) {
        RefreshTokenFamilyState revoked = new RefreshTokenFamilyState(UUID.randomUUID(), ACCOUNT,
                true, CONSUMED_AT, CONSUMED_AT.plus(Duration.ofHours(12)),
                CONSUMED_AT.plusSeconds(1));

        assertThat(RotationDecision.decide(revoked, token, ONE, CONSUMED_AT.plusMillis(millisAfter),
                POLICY)).isEqualTo(RotationDecision.REJECT_FAMILY_REVOKED);
    }

    @Provide
    Arbitrary<RefreshTokenState> tokens() {
        return Arbitraries.of(false, true).flatMap(isConsumed -> isConsumed
                ? Arbitraries.of(ONE, TWO).map(by -> consumed(by, true, false))
                : Arbitraries.just(new RefreshTokenState(UUID.randomUUID(), UUID.randomUUID(),
                        CONSUMED_AT.minusSeconds(60), CONSUMED_AT.plusSeconds(3600), null, null,
                        false, false)));
    }

    private static RefreshTokenState consumed(UserAgentFingerprint by, boolean ciphertext,
            boolean successorConsumed) {
        return new RefreshTokenState(UUID.randomUUID(), UUID.randomUUID(),
                CONSUMED_AT.minusSeconds(60), CONSUMED_AT.plus(Duration.ofHours(8)), CONSUMED_AT,
                by, ciphertext, successorConsumed);
    }
}
