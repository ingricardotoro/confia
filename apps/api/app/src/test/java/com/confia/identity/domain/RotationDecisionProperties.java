package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;

/**
 * The binding of the session against the decision that existed before it
 * (session-endpoints-cookie-and-csrf design.md decision 14; scenarios I121, I190 to I192 and I225).
 * The reference below is the table of the previous change written out once more, so a property can
 * say "with the binding unchanged the result is the one of that change" for any family, token,
 * presenter and instant, and "a changed binding only ever replaces the three decisions that would
 * have handed credentials out or answered on the token itself".
 */
class RotationDecisionProperties {

    private static final Instant EIGHT = Instant.parse("2026-10-12T08:00:00Z");
    private static final Instant ABSOLUTE_END = Instant.parse("2026-10-12T20:00:00Z");
    private static final StaffAccountId ACCOUNT = new StaffAccountId(UUID.randomUUID());
    private static final SessionLifetimePolicy POLICY = new SessionLifetimePolicy();
    private static final UserAgentFingerprint ONE = UserAgentFingerprint.of("agente-uno");
    private static final UserAgentFingerprint TWO = UserAgentFingerprint.of("agente-dos");
    private static final UserAgentFingerprint NONE = UserAgentFingerprint.of(null);

    @Property(tries = 2000)
    void withTheBindingUnchangedTheResultIsTheOneBeforeTheBinding(
            @ForAll("families") RefreshTokenFamilyState family,
            @ForAll("tokens") RefreshTokenState token,
            @ForAll("agents") UserAgentFingerprint presenter,
            @ForAll @LongRange(min = -1_000, max = 50_000_000) long millisAfter) {
        Instant now = EIGHT.plusMillis(millisAfter);

        RotationDecision withoutAddressChange = RotationDecision.decide(family, token, presenter,
                false, now, POLICY);
        assertThat(withoutAddressChange).isEqualTo(beforeTheBinding(family, token, presenter, now));

        if (family.creationFingerprint().equals(presenter)) {
            assertThat(RotationDecision.decide(family, token, presenter, true, now, POLICY))
                    .as("the agent did not change either")
                    .isEqualTo(withoutAddressChange);
        }
    }

    @Property(tries = 2000)
    void aChangedBindingOnlyReplacesWhatWouldHaveRotatedReplayedOrExpired(
            @ForAll("families") RefreshTokenFamilyState family,
            @ForAll("tokens") RefreshTokenState token,
            @ForAll("agents") UserAgentFingerprint presenter,
            @ForAll @LongRange(min = -1_000, max = 50_000_000) long millisAfter) {
        Instant now = EIGHT.plusMillis(millisAfter);
        RotationDecision before = beforeTheBinding(family, token, presenter, now);
        boolean agentChanged = !family.creationFingerprint().equals(NONE)
                && !family.creationFingerprint().equals(presenter);

        RotationDecision decision = RotationDecision.decide(family, token, presenter, true, now,
                POLICY);

        boolean replaced = agentChanged && (before == RotationDecision.ROTATE
                || before == RotationDecision.GRACE_REPLAY
                || before == RotationDecision.REJECT_TOKEN_EXPIRED);
        assertThat(decision).isEqualTo(replaced ? RotationDecision.REJECT_BINDING_CHANGED : before);
    }

    /** The decision of the previous change, written out in its order. */
    private static RotationDecision beforeTheBinding(RefreshTokenFamilyState family,
            RefreshTokenState token, UserAgentFingerprint presenter, Instant now) {
        if (family.isRevoked()) {
            return RotationDecision.REJECT_FAMILY_REVOKED;
        }
        if (!now.isBefore(family.absoluteExpiresAt())
                || !now.isBefore(family.lastUsedAt().plusSeconds(30 * 60))) {
            return RotationDecision.REJECT_FAMILY_EXPIRED;
        }
        if (token.isConsumed()) {
            boolean replayable = now.isBefore(token.consumedAt().plusSeconds(10))
                    && presenter.equals(token.consumedUserAgentFingerprint())
                    && token.successorCiphertextPresent() && !token.successorConsumed();
            return replayable ? RotationDecision.GRACE_REPLAY : RotationDecision.REUSE_DETECTED;
        }
        return !now.isBefore(token.expiresAt()) ? RotationDecision.REJECT_TOKEN_EXPIRED
                : RotationDecision.ROTATE;
    }

    @Provide
    Arbitrary<UserAgentFingerprint> agents() {
        return Arbitraries.of(ONE, TWO, NONE);
    }

    @Provide
    Arbitrary<RefreshTokenFamilyState> families() {
        Arbitrary<UserAgentFingerprint> created = Arbitraries.of(ONE, TWO, NONE);
        Arbitrary<Instant> lastUsed = Arbitraries.longs().between(-3_600, 3_600)
                .map(EIGHT::plusSeconds);
        Arbitrary<Instant> revoked = Arbitraries.of(true, false, false, false)
                .map(isRevoked -> isRevoked ? EIGHT.plusSeconds(1) : null);
        return Combinators.combine(created, lastUsed, revoked).as((fingerprint, used, revokedAt) ->
                new RefreshTokenFamilyState(UUID.randomUUID(), ACCOUNT, false, used,
                        ABSOLUTE_END, revokedAt, fingerprint, null));
    }

    @Provide
    Arbitrary<RefreshTokenState> tokens() {
        Arbitrary<Instant> expiresAt = Arbitraries.longs().between(-3_600, 36_000)
                .map(EIGHT::plusSeconds);
        Arbitrary<Instant> consumedAt = Arbitraries.longs().between(-30, 30)
                .map(EIGHT::plusSeconds);
        Arbitrary<Boolean> flag = Arbitraries.of(true, false);
        return Combinators.combine(expiresAt, consumedAt, Arbitraries.of(true, false, false),
                Arbitraries.of(ONE, TWO, NONE), flag, flag).as((expires, consumed, isConsumed, by,
                        ciphertext, advanced) -> isConsumed
                        ? new RefreshTokenState(UUID.randomUUID(), UUID.randomUUID(),
                                EIGHT.minusSeconds(60), expires, consumed, by, ciphertext, advanced)
                        : new RefreshTokenState(UUID.randomUUID(), UUID.randomUUID(),
                                EIGHT.minusSeconds(60), expires, null, null, false, false));
    }
}
