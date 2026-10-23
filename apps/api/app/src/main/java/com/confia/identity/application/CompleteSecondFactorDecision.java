package com.confia.identity.application;

import java.time.Duration;
import java.util.Objects;

/**
 * What {@link CompleteSecondFactor} returns once its transaction committed: the outcome and the
 * delay the caller must still materialize (session-endpoints-cookie-and-csrf design.md, decision 13).
 * The use case never waits itself (specs/identity/spec.md, "Ninguna clase del módulo de identidad
 * espera"): the delay of the shared TOTP backoff is computed before the proof is judged and owed on
 * the answer, whatever it is.
 *
 * @param outcome whether a session was opened
 * @param requiredDelay the delay the edge still owes; never negative
 */
public record CompleteSecondFactorDecision(SecondFactorOutcome outcome, Duration requiredDelay) {

    public CompleteSecondFactorDecision {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(requiredDelay, "requiredDelay");
        if (requiredDelay.isNegative()) {
            throw new IllegalArgumentException("requiredDelay must not be negative, was "
                    + requiredDelay);
        }
    }
}
