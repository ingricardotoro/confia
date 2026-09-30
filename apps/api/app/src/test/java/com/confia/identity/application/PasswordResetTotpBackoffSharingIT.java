package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.ResetOutcome.Completed;
import com.confia.identity.application.ResetOutcome.SecondFactorRejected;
import com.confia.identity.domain.AuthenticationResult.SecondFactorRequired;
import com.confia.identity.domain.PlainPasswordResetToken;
import com.confia.identity.domain.PlainTotpSecret;
import com.confia.identity.domain.StaffAccountId;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * A wrong TOTP code in a password reset advances the very same counter as the verification after
 * login (password-recovery-token design.md decision 7; specs/identity/spec.md, "Un segundo factor
 * incorrecto en el restablecimiento avanza el mismo retroceso TOTP que el inicio de sesión").
 *
 * <p>The reset reaches it through {@code VerifyTotpCode.runWithinTransaction}, so both paths share
 * {@code TotpVerificationBackoffStore}, keyed by {@code (institution_id, account_id)} in {@code
 * identity_mfa_totp_backoff}, and the same {@code new BackoffPolicy()}: {@code
 * FIRST_DELAYED_ATTEMPT = 3}, {@code CAP = 900 s} and {@code COUNTER_WINDOW = 30 min}. With a
 * counter of its own, the third failure below would require 0 s instead of 1 s; with a window other
 * than thirty minutes, the attempt at 09:35 would not start a new cycle. Every clock is fixed.
 */
class PasswordResetTotpBackoffSharingIT extends PasswordResetIntegrationTest {

    private static final String SOFIA = "sofia.mejia@colegio.edu.hn";
    private static final String CURRENT = "Cafetal de Copán 2026";
    private static final String NEW = "Montaña de Celaque 2026";

    /** Scenario I13: login and reset failures add up in one counter. */
    @Test
    void loginAndResetFailuresAddUpInTheSameCounter() {
        StaffAccountId sofia = seedStaffAccount(SOFIA, CURRENT, true);
        PlainTotpSecret secret = enrollTotp(sofia);
        PlainPasswordResetToken token = issueTokenAt(sofia, "2026-10-14T09:00:00Z");

        assertThat(verifyAfterLoginAt(sofia, wrongCodeAt(secret, "2026-10-14T09:01:00Z"),
                "2026-10-14T09:01:00Z").requiredDelay()).isEqualTo(Duration.ZERO);
        assertThat(verifyAfterLoginAt(sofia, wrongCodeAt(secret, "2026-10-14T09:02:00Z"),
                "2026-10-14T09:02:00Z").requiredDelay()).isEqualTo(Duration.ZERO);

        ResetPasswordDecision third = resetAt(token.value(), NEW, new SecondFactorProof.Totp(
                wrongCodeAt(secret, "2026-10-14T09:03:00Z")), "2026-10-14T09:03:00Z");

        assertThat(third.outcome()).isInstanceOf(SecondFactorRejected.class);
        assertThat(third.requiredDelay())
                .as("the third consecutive failure: 2^(3-3) = 1 s; a separate counter gives 0")
                .isEqualTo(Duration.ofSeconds(1));

        assertThat(verifyAfterLoginAt(sofia, wrongCodeAt(secret, "2026-10-14T09:04:00Z"),
                "2026-10-14T09:04:00Z").requiredDelay())
                .as("the fourth failure, back on the login path: 2^(4-3) = 2 s")
                .isEqualTo(Duration.ofSeconds(2));

        ResetPasswordDecision afterWindow = resetAt(token.value(), NEW, new SecondFactorProof.Totp(
                wrongCodeAt(secret, "2026-10-14T09:35:00Z")), "2026-10-14T09:35:00Z");

        assertThat(afterWindow.requiredDelay())
                .as("thirty-one minutes after the last attempt a new cycle starts without delay")
                .isEqualTo(Duration.ZERO);
        assertThat(isOpen(token)).isTrue();
    }

    /** Scenario I14: the failure is committed and the token still serves. */
    @Test
    void theFailureIsCommittedAndTheTokenStillServes() {
        StaffAccountId sofia = seedStaffAccount(SOFIA, CURRENT, true);
        PlainTotpSecret secret = enrollTotp(sofia);
        PlainPasswordResetToken token = issueTokenAt(sofia, "2026-10-14T14:00:00Z");

        ResetPasswordDecision rejected = resetAt(token.value(), NEW, new SecondFactorProof.Totp(
                wrongCodeAt(secret, "2026-10-14T14:02:00Z")), "2026-10-14T14:02:00Z");

        assertThat(rejected.outcome()).isInstanceOf(SecondFactorRejected.class);
        assertThat(loginAt(SOFIA, CURRENT, "2026-10-14T14:02:30Z"))
                .as("the current password did not change")
                .isInstanceOf(SecondFactorRequired.class);
        assertThat(consecutiveTotpFailures(sofia))
                .as("the counter advance was committed, not rolled back with the rejection")
                .isEqualTo(1);
        assertThat(auditRows("identity.mfa.totp_verification.failed")).hasSize(1);
        assertThat(auditRows("identity.password_reset.rejected")).singleElement()
                .extracting(row -> row.afterValue())
                .asString().contains("second-factor-invalid");

        ResetPasswordDecision accepted = resetAt(token.value(), NEW, new SecondFactorProof.Totp(
                validCodeAt(secret, "2026-10-14T14:03:00Z")), "2026-10-14T14:03:00Z");

        assertThat(accepted.outcome()).isInstanceOf(Completed.class);
    }

    private int consecutiveTotpFailures(StaffAccountId accountId) {
        return transactionRunner().execute(context(), () -> dsl.fetchOne("""
                select consecutive_failures from identity_mfa_totp_backoff
                where institution_id = ? and account_id = ?
                """, institutionId.value(), accountId.value())
                .get("consecutive_failures", Integer.class));
    }
}
