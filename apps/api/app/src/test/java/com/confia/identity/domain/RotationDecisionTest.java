package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The whole table of {@link RotationDecision} (session-tokens-and-web-layer design.md decision 10 and
 * session-endpoints-cookie-and-csrf design.md decision 14, which adds the binding; scenarios I91, I109
 * to I112, I142, I145, I190 to I192 and I225): the seven decisions, the fixed order they are evaluated
 * in, and the exact instant at which each border flips. The decision is a pure function, so every
 * instant is given and nothing here reads a clock.
 */
class RotationDecisionTest {

    private static final Instant EIGHT = Instant.parse("2026-10-12T08:00:00Z");
    private static final Instant ABSOLUTE_END = Instant.parse("2026-10-12T20:00:00Z");
    private static final StaffAccountId ACCOUNT = new StaffAccountId(UUID.randomUUID());
    private static final UserAgentFingerprint ONE = UserAgentFingerprint.of("agente-uno");
    private static final UserAgentFingerprint TWO = UserAgentFingerprint.of("agente-dos");
    private static final SessionLifetimePolicy POLICY = new SessionLifetimePolicy();

    // --- Order 7: a fresh rotation -----------------------------------------------------------

    @Test
    void anOpenTokenOfALiveFamilyRotates() {
        assertThat(decide(live(EIGHT), open(EIGHT), ONE, "2026-10-12T08:15:00Z"))
                .isEqualTo(RotationDecision.ROTATE);
    }

    @Test
    void theAgentOfThePresenterNeverPreventsARotation() {
        // Scenario I121: the family was opened by one agent and is presented by another.
        assertThat(decide(live(EIGHT), open(EIGHT), TWO, "2026-10-12T08:15:00Z"))
                .isEqualTo(RotationDecision.ROTATE);
    }

    // --- Order 6: the token's own expiry (8 hours, clipped to the family) --------------------

    @Test
    void aTokenIsAcceptedUntilEightHoursAfterItsIssuanceAndRejectedAtThatInstant() {
        RefreshTokenFamilyState family = live(Instant.parse("2026-10-12T15:59:30Z"));
        RefreshTokenState token = open(EIGHT);

        assertThat(token.expiresAt()).isEqualTo(Instant.parse("2026-10-12T16:00:00Z"));
        assertThat(decide(family, token, ONE, "2026-10-12T15:59:59Z"))
                .isEqualTo(RotationDecision.ROTATE);
        assertThat(decide(family, token, ONE, "2026-10-12T16:00:00Z"))
                .as("scenario I112: the instant of expiry is already expired")
                .isEqualTo(RotationDecision.REJECT_TOKEN_EXPIRED);
        assertThat(decide(family, token, ONE, "2026-10-12T16:00:01Z"))
                .isEqualTo(RotationDecision.REJECT_TOKEN_EXPIRED);
    }

    // --- Order 2: the family's expiry (30 minutes idle, 12 hours absolute) -------------------

    @Test
    void aFamilyIsIdleExactlyThirtyMinutesAfterItsLastUse() {
        RefreshTokenFamilyState family = live(EIGHT);

        assertThat(decide(family, open(EIGHT), ONE, "2026-10-12T08:29:59Z"))
                .as("scenario I109: one second before the border it rotates")
                .isEqualTo(RotationDecision.ROTATE);
        assertThat(decide(family, open(EIGHT), ONE, "2026-10-12T08:30:00Z"))
                .as("scenario I109: the border is already expired")
                .isEqualTo(RotationDecision.REJECT_FAMILY_EXPIRED);
        assertThat(decide(family, open(EIGHT), ONE, "2026-10-12T08:30:01Z"))
                .isEqualTo(RotationDecision.REJECT_FAMILY_EXPIRED);
    }

    @Test
    void aFamilyIsAbsolutelyExpiredAtItsEndAndAcceptedASecondBefore() {
        RefreshTokenFamilyState family = live(Instant.parse("2026-10-12T19:59:00Z"));
        RefreshTokenState token = new RefreshTokenState(UUID.randomUUID(), family.id(),
                Instant.parse("2026-10-12T12:00:00Z"), ABSOLUTE_END, null, null, false, false);

        assertThat(decide(family, token, ONE, "2026-10-12T19:59:59Z"))
                .as("scenario I110: the token clipped to the end of the family still rotates")
                .isEqualTo(RotationDecision.ROTATE);
        assertThat(decide(family, token, ONE, "2026-10-12T20:00:00Z"))
                .as("scenario I111: the absolute end is already expired")
                .isEqualTo(RotationDecision.REJECT_FAMILY_EXPIRED);
    }

    @Test
    void aConsumedTokenPresentedToAnExpiredFamilyIsAnExpiryAndNotAReuse() {
        // Scenario I142, both of its cases: idle, and absolutely expired.
        RefreshTokenState consumed = consumed(EIGHT, ONE, true, false);

        assertThat(decide(live(EIGHT), consumed, ONE, "2026-10-12T08:30:00Z"))
                .isEqualTo(RotationDecision.REJECT_FAMILY_EXPIRED);
        RefreshTokenFamilyState endsSoon = new RefreshTokenFamilyState(UUID.randomUUID(), ACCOUNT,
                false, EIGHT, Instant.parse("2026-10-12T08:00:30Z"), null);
        assertThat(decide(endsSoon, consumed, ONE, "2026-10-12T08:00:30Z"))
                .isEqualTo(RotationDecision.REJECT_FAMILY_EXPIRED);
    }

    // --- Order 1: a revoked family ------------------------------------------------------------

    @Test
    void aRevokedFamilyRejectsBeforeAnythingElseIsLookedAt() {
        RefreshTokenFamilyState revoked = new RefreshTokenFamilyState(UUID.randomUUID(), ACCOUNT,
                false, EIGHT, ABSOLUTE_END, Instant.parse("2026-10-12T08:05:00Z"));

        assertThat(decide(revoked, open(EIGHT), ONE, "2026-10-12T08:10:00Z"))
                .isEqualTo(RotationDecision.REJECT_FAMILY_REVOKED);
        assertThat(decide(revoked, consumed(EIGHT, ONE, true, false), ONE, "2026-10-12T08:00:05Z"))
                .as("a revoked family is not a grace window")
                .isEqualTo(RotationDecision.REJECT_FAMILY_REVOKED);
        assertThat(decide(revoked, open(EIGHT), ONE, "2026-10-12T21:00:00Z"))
                .as("revoked wins over expired: the order is fixed")
                .isEqualTo(RotationDecision.REJECT_FAMILY_REVOKED);
    }

    // --- Orders 3 and 5: a consumed token, reuse or grace ------------------------------------

    @Test
    void aRetryFromTheSameDeviceInsideTenSecondsReplaysTheGrace() {
        RefreshTokenState token = consumed(EIGHT, ONE, true, false);

        assertThat(decide(live(EIGHT), token, ONE, "2026-10-12T08:00:00Z"))
                .isEqualTo(RotationDecision.GRACE_REPLAY);
        assertThat(decide(live(EIGHT), token, ONE, "2026-10-12T08:00:09Z"))
                .isEqualTo(RotationDecision.GRACE_REPLAY);
    }

    @Test
    void theGraceWindowEndsExactlyTenSecondsAfterTheConsumption() {
        RefreshTokenState token = consumed(EIGHT, ONE, true, false);
        Instant nineAndABit = EIGHT.plusMillis(9_999);

        assertThat(decide(live(EIGHT), token, ONE, nineAndABit))
                .isEqualTo(RotationDecision.GRACE_REPLAY);
        assertThat(decide(live(EIGHT), token, ONE, EIGHT.plusSeconds(10)))
                .as("ten seconds exactly is already a reuse").isEqualTo(RotationDecision.REUSE_DETECTED);
        assertThat(decide(live(EIGHT), token, ONE, EIGHT.plusSeconds(11)))
                .isEqualTo(RotationDecision.REUSE_DETECTED);
    }

    @Test
    void anotherDeviceInsideTheWindowIsAReuse() {
        assertThat(decide(live(EIGHT), consumed(EIGHT, ONE, true, false), TWO,
                "2026-10-12T08:00:03Z")).isEqualTo(RotationDecision.REUSE_DETECTED);
    }

    @Test
    void withoutTheEncryptedSuccessorThereIsNothingToReplay() {
        assertThat(decide(live(EIGHT), consumed(EIGHT, ONE, false, false), ONE,
                "2026-10-12T08:00:03Z")).isEqualTo(RotationDecision.REUSE_DETECTED);
    }

    @Test
    void aSuccessorThatAlreadyAdvancedGivesNoGrace() {
        assertThat(decide(live(EIGHT), consumed(EIGHT, ONE, true, true), ONE,
                "2026-10-12T08:00:03Z")).isEqualTo(RotationDecision.REUSE_DETECTED);
    }

    @Test
    void aConsumedTokenWhoseOwnExpiryPassedIsAReuseAndNotAnExpiry() {
        // Scenario I145: consumption is evaluated before the token's own expiry.
        RefreshTokenFamilyState family = live(Instant.parse("2026-10-12T16:30:00Z"));
        RefreshTokenState consumed = new RefreshTokenState(UUID.randomUUID(), family.id(), EIGHT,
                Instant.parse("2026-10-12T16:00:00Z"), EIGHT.plusSeconds(60), ONE, false, false);

        assertThat(decide(family, consumed, ONE, "2026-10-12T16:30:10Z"))
                .isEqualTo(RotationDecision.REUSE_DETECTED);
    }

    // --- Order 4: the binding of the session (agent and address both changed) ----------------

    @Test
    void anAgentAndAnAddressThatBothChangedRejectAnOpenToken() {
        // Scenario I190.
        assertThat(decideFromAnotherAddress(live(EIGHT), open(EIGHT), TWO, "2026-10-12T08:15:00Z"))
                .isEqualTo(RotationDecision.REJECT_BINDING_CHANGED);
    }

    @Test
    void onlyOneOfTheTwoChangingStillRotates() {
        // Scenarios I121 and I191.
        assertThat(decide(live(EIGHT), open(EIGHT), TWO, "2026-10-12T08:15:00Z"))
                .as("only the agent changed").isEqualTo(RotationDecision.ROTATE);
        assertThat(decideFromAnotherAddress(live(EIGHT), open(EIGHT), ONE, "2026-10-12T08:15:00Z"))
                .as("only the address changed").isEqualTo(RotationDecision.ROTATE);
    }

    @Test
    void aFamilyOpenedWithoutAnAgentHasNoAgentToCompareAgainst() {
        // Scenario I192: the stored fingerprint is that of no text, so it is not a binding.
        assertThat(decideFromAnotherAddress(liveWithoutOrigin(EIGHT), open(EIGHT), TWO,
                "2026-10-12T08:15:00Z")).isEqualTo(RotationDecision.ROTATE);
    }

    @Test
    void aPresenterWithoutAnAgentDiffersFromAnAgentThatWasRecorded() {
        assertThat(decideFromAnotherAddress(live(EIGHT), open(EIGHT), UserAgentFingerprint.of(null),
                "2026-10-12T08:15:00Z")).isEqualTo(RotationDecision.REJECT_BINDING_CHANGED);
    }

    @Test
    void theBindingIsEvaluatedAfterTheStateOfTheFamilyAndAfterReuse() {
        // Scenario I225: reuse before binding, and the state of the family before both.
        RefreshTokenState consumedOutsideGrace = consumed(EIGHT, ONE, true, false);
        assertThat(decideFromAnotherAddress(live(EIGHT), consumedOutsideGrace, TWO,
                "2026-10-12T08:00:30Z")).isEqualTo(RotationDecision.REUSE_DETECTED);

        RefreshTokenFamilyState revoked = new RefreshTokenFamilyState(UUID.randomUUID(), ACCOUNT,
                false, EIGHT, ABSOLUTE_END, Instant.parse("2026-10-12T08:05:00Z"), ONE,
                "203.0.113.9");
        assertThat(decideFromAnotherAddress(revoked, open(EIGHT), TWO, "2026-10-12T08:10:00Z"))
                .isEqualTo(RotationDecision.REJECT_FAMILY_REVOKED);
        assertThat(decideFromAnotherAddress(live(EIGHT), open(EIGHT), TWO, "2026-10-12T08:30:00Z"))
                .isEqualTo(RotationDecision.REJECT_FAMILY_EXPIRED);
    }

    @Test
    void theBindingIsEvaluatedBeforeTheGraceAndBeforeTheExpiryOfTheToken() {
        // Scenario I225: a token that would be replayed, or that only expired, is a change of binding.
        RefreshTokenState replayable = consumed(EIGHT, TWO, true, false);
        assertThat(decide(live(EIGHT), replayable, TWO, "2026-10-12T08:00:03Z"))
                .as("without the change of address it is a grace replay")
                .isEqualTo(RotationDecision.GRACE_REPLAY);
        assertThat(decideFromAnotherAddress(live(EIGHT), replayable, TWO, "2026-10-12T08:00:03Z"))
                .isEqualTo(RotationDecision.REJECT_BINDING_CHANGED);

        RefreshTokenFamilyState family = live(Instant.parse("2026-10-12T15:59:30Z"));
        assertThat(decideFromAnotherAddress(family, open(EIGHT), TWO, "2026-10-12T16:00:00Z"))
                .as("the token expired at 16:00 and the binding changed")
                .isEqualTo(RotationDecision.REJECT_BINDING_CHANGED);
    }

    // --- What each decision becomes for the caller -------------------------------------------

    @Test
    void aChangedBindingIsReportedAsBindingChanged() {
        assertThat(RotationDecision.REJECT_BINDING_CHANGED.rejection())
                .contains(RefreshRejection.BINDING_CHANGED);
        assertThat(RefreshRejection.BINDING_CHANGED.auditCode()).isEqualTo("binding_changed");
    }

    @Test
    void everyRejectionNamesItsReasonAndThePassingDecisionsNameNone() {
        assertThat(RotationDecision.REJECT_FAMILY_REVOKED.rejection())
                .contains(RefreshRejection.FAMILY_REVOKED);
        assertThat(RotationDecision.REJECT_FAMILY_EXPIRED.rejection())
                .contains(RefreshRejection.FAMILY_EXPIRED);
        assertThat(RotationDecision.REJECT_TOKEN_EXPIRED.rejection())
                .contains(RefreshRejection.TOKEN_EXPIRED);
        assertThat(RotationDecision.REUSE_DETECTED.rejection())
                .contains(RefreshRejection.REUSE_DETECTED);
        assertThat(RotationDecision.ROTATE.rejection()).isEqualTo(Optional.empty());
        assertThat(RotationDecision.GRACE_REPLAY.rejection()).isEqualTo(Optional.empty());
    }

    // ---------------------------------------------------------------------------------------------

    private static RotationDecision decide(RefreshTokenFamilyState family, RefreshTokenState token,
            UserAgentFingerprint presenter, String now) {
        return decide(family, token, presenter, Instant.parse(now));
    }

    private static RotationDecision decide(RefreshTokenFamilyState family, RefreshTokenState token,
            UserAgentFingerprint presenter, Instant now) {
        return RotationDecision.decide(family, token, presenter, false, now, POLICY);
    }

    /** The address of the presenter differs from the one the family was opened from. */
    private static RotationDecision decideFromAnotherAddress(RefreshTokenFamilyState family,
            RefreshTokenState token, UserAgentFingerprint presenter, String now) {
        return RotationDecision.decide(family, token, presenter, true, Instant.parse(now), POLICY);
    }

    /** A live family opened by agent one from an address, that ends at 20:00. */
    private static RefreshTokenFamilyState live(Instant lastUsedAt) {
        return new RefreshTokenFamilyState(UUID.randomUUID(), ACCOUNT, false, lastUsedAt,
                ABSOLUTE_END, null, ONE, "203.0.113.9");
    }

    /** A live family opened with no agent and no address, as an issuance without a request leaves it. */
    private static RefreshTokenFamilyState liveWithoutOrigin(Instant lastUsedAt) {
        return new RefreshTokenFamilyState(UUID.randomUUID(), ACCOUNT, false, lastUsedAt,
                ABSOLUTE_END, null, UserAgentFingerprint.of(null), null);
    }

    /** An unconsumed token issued at {@code issuedAt}, with the 8 hours of the policy. */
    private static RefreshTokenState open(Instant issuedAt) {
        return new RefreshTokenState(UUID.randomUUID(), UUID.randomUUID(), issuedAt,
                POLICY.refreshExpiresAt(issuedAt, ABSOLUTE_END), null, null, false, false);
    }

    private static RefreshTokenState consumed(Instant consumedAt, UserAgentFingerprint by,
            boolean ciphertextPresent, boolean successorConsumed) {
        return new RefreshTokenState(UUID.randomUUID(), UUID.randomUUID(), EIGHT.minusSeconds(60),
                EIGHT.plusSeconds(8 * 3600), consumedAt, by, ciphertextPresent, successorConsumed);
    }
}
