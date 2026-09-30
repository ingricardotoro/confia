package com.confia.identity.application;

import java.time.Duration;
import java.util.Objects;

/**
 * A password reset's outcome, plus the delay the shared TOTP backoff requires before the next
 * attempt when a TOTP code was checked, and zero otherwise (password-recovery-token design.md
 * decision 7).
 */
public record ResetPasswordDecision(ResetOutcome outcome, Duration requiredDelay) {

    public ResetPasswordDecision {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(requiredDelay, "requiredDelay");
        if (requiredDelay.isNegative()) {
            throw new IllegalArgumentException("requiredDelay must not be negative");
        }
    }
}
