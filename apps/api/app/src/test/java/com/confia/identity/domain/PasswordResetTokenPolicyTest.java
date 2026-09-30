package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link PasswordResetTokenPolicy} (password-recovery-token design.md decision 4): the single source
 * of the 30-minute validity, the 60-minute issuance window and its limit of three, and of the order
 * in which a stored token's rejection reasons are reported. Every instant is fixed; the policy has
 * no clock of its own.
 */
class PasswordResetTokenPolicyTest {

    private static final Instant TEN = Instant.parse("2026-10-05T10:00:00Z");

    private final PasswordResetTokenPolicy policy = new PasswordResetTokenPolicy();

    @Test
    void theConstantsAreExactlyThoseOfTheSpecification() {
        assertThat(PasswordResetTokenPolicy.VALIDITY).isEqualTo(Duration.ofMinutes(30));
        assertThat(PasswordResetTokenPolicy.ISSUANCE_WINDOW).isEqualTo(Duration.ofMinutes(60));
        assertThat(PasswordResetTokenPolicy.MAX_ISSUANCES_PER_WINDOW).isEqualTo(3);
    }

    @Test
    void aTokenExpiresThirtyMinutesAfterItIsIssued() {
        assertThat(policy.expiresAt(TEN)).isEqualTo(Instant.parse("2026-10-05T10:30:00Z"));
    }

    /** Scenario "El borde de los treinta minutos es estricto" (I37, unit part). */
    @Test
    void aTokenIssuedAtTenIsValidAtTenTwentyNineFiftyNineAndNoLongerAtTenThirty() {
        PasswordResetTokenRow row = openRowIssuedAt(TEN);

        assertThat(policy.rejectionReasonOf(row, Instant.parse("2026-10-05T10:29:59Z"))).isEmpty();
        assertThat(policy.rejectionReasonOf(row, Instant.parse("2026-10-05T10:30:00Z")))
                .contains(PasswordResetRejectionReason.TOKEN_EXPIRED);
    }

    /**
     * Scenario "La ventana es de sesenta minutos móviles" (I6, unit part): issuances at 10:00, 10:10
     * and 10:20 block a fourth at 10:30, and allow it at 11:00:00, because one issued exactly sixty
     * minutes earlier has already left the window, and at 11:00:01.
     */
    @Test
    void theIssuanceWindowIsSixtyRollingMinutesWithTheExactBorderOutside() {
        List<Instant> issuances = List.of(TEN, Instant.parse("2026-10-05T10:10:00Z"),
                Instant.parse("2026-10-05T10:20:00Z"));

        assertThat(allowsAt(issuances, Instant.parse("2026-10-05T10:30:00Z"))).isFalse();
        assertThat(allowsAt(issuances, Instant.parse("2026-10-05T11:00:00Z"))).isTrue();
        assertThat(allowsAt(issuances, Instant.parse("2026-10-05T11:00:01Z"))).isTrue();
    }

    @Test
    void theWindowStartIsSixtyMinutesBeforeNow() {
        assertThat(policy.issuanceWindowStart(Instant.parse("2026-10-05T11:00:00Z"))).isEqualTo(TEN);
    }

    @Test
    void anotherIssuanceIsAllowedBelowThreeAndRefusedAtThree() {
        assertThat(policy.allowsAnotherIssuance(0)).isTrue();
        assertThat(policy.allowsAnotherIssuance(2)).isTrue();
        assertThat(policy.allowsAnotherIssuance(3)).isFalse();
        assertThat(policy.allowsAnotherIssuance(4)).isFalse();
    }

    @Test
    void aConsumedTokenIsReportedAsConsumedEvenOnceExpired() {
        PasswordResetTokenRow consumed = new PasswordResetTokenRow(UUID.randomUUID(), anAccount(),
                TEN, policy.expiresAt(TEN), TEN.plusSeconds(60), null);

        assertThat(policy.rejectionReasonOf(consumed, TEN.plusSeconds(120)))
                .contains(PasswordResetRejectionReason.TOKEN_CONSUMED);
        assertThat(policy.rejectionReasonOf(consumed, TEN.plus(Duration.ofHours(2))))
                .contains(PasswordResetRejectionReason.TOKEN_CONSUMED);
    }

    /** The precedence is consumed, then superseded, then expired (design.md, section 0). */
    @Test
    void anExpiredAndSupersededTokenIsReportedAsSuperseded() {
        PasswordResetTokenRow superseded = new PasswordResetTokenRow(UUID.randomUUID(),
                anAccount(), TEN, policy.expiresAt(TEN), null, TEN.plusSeconds(60));

        assertThat(policy.rejectionReasonOf(superseded, TEN.plusSeconds(120)))
                .contains(PasswordResetRejectionReason.TOKEN_SUPERSEDED);
        assertThat(policy.rejectionReasonOf(superseded, TEN.plus(Duration.ofHours(2))))
                .contains(PasswordResetRejectionReason.TOKEN_SUPERSEDED);
    }

    @Test
    void everyReasonHasTheAuditCodeOfDecisionNine() {
        assertThat(PasswordResetRejectionReason.values()).extracting(
                PasswordResetRejectionReason::auditCode).containsExactly("token-not-found",
                "token-expired", "token-superseded", "token-consumed", "password-too-short",
                "password-too-long", "second-factor-missing", "second-factor-invalid");
    }

    /** The same predicate as the adapter's {@code issued_at > since}, applied to fixed instants. */
    private boolean allowsAt(List<Instant> issuances, Instant now) {
        Instant windowStart = policy.issuanceWindowStart(now);
        long issuedInWindow = issuances.stream()
                .filter(issuedAt -> issuedAt.isAfter(windowStart) && !issuedAt.isAfter(now))
                .count();
        return policy.allowsAnotherIssuance(issuedInWindow);
    }

    private PasswordResetTokenRow openRowIssuedAt(Instant issuedAt) {
        return new PasswordResetTokenRow(UUID.randomUUID(), anAccount(), issuedAt,
                policy.expiresAt(issuedAt), null, null);
    }

    private static StaffAccountId anAccount() {
        return new StaffAccountId(UUID.randomUUID());
    }
}
