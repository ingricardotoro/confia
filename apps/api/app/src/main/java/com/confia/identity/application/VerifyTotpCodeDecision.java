package com.confia.identity.application;

import java.time.Duration;
import java.util.Objects;

/**
 * Result of {@link VerifyTotpCode}, and the wait the caller MUST honor before responding — the
 * same six-point contract {@link AuthenticationDecision} already documents for the password step
 * (column-encryption-and-mfa-totp design.md, §4.2, decision 8): {@code requiredDelay} is counted
 * from the moment this record is returned, with the transaction already committed, and never
 * materialized inside any transaction.
 *
 * <p>The backoff delays the response and does NOT decide {@code accepted} on its own: a correct
 * code presented mid-backoff still accepts, after paying the delay — exactly {@link
 * AuthenticationDecision}'s own "la contraseña correcta durante el retroceso" property, applied
 * here to a TOTP code.
 */
public record VerifyTotpCodeDecision(boolean accepted, Duration requiredDelay) {

    public VerifyTotpCodeDecision {
        Objects.requireNonNull(requiredDelay, "requiredDelay");
        if (requiredDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "requiredDelay must not be negative, was " + requiredDelay);
        }
    }
}
