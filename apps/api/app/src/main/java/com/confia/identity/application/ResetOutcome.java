package com.confia.identity.application;

import com.confia.identity.domain.PasswordResetRejectionReason;
import java.util.Objects;

/**
 * The outcome of a password reset (password-recovery-token design.md decision 7). Every consumer is
 * an exhaustive {@code switch} with no {@code default}. {@link TokenRejected} carries no reason: a
 * token that was never issued, has expired, was superseded or was already used, and a token of
 * another institution, all give the same value, and the real reason is only in the audit log.
 */
public sealed interface ResetOutcome {

    record Completed() implements ResetOutcome {
    }

    record TokenRejected() implements ResetOutcome {
    }

    /** The new password is outside 12 to 128 characters; the token is still alive. */
    record PasswordRejected(PasswordResetRejectionReason reason) implements ResetOutcome {

        public PasswordRejected {
            Objects.requireNonNull(reason, "reason");
            if (reason != PasswordResetRejectionReason.PASSWORD_TOO_SHORT
                    && reason != PasswordResetRejectionReason.PASSWORD_TOO_LONG) {
                throw new IllegalArgumentException("a password rejection is too short or too long");
            }
        }
    }

    /** The account has active MFA and no second factor was presented; the token is still alive. */
    record SecondFactorMissing() implements ResetOutcome {
    }

    /** The second factor was wrong; its backoff was committed and the token is still alive. */
    record SecondFactorRejected() implements ResetOutcome {
    }
}
