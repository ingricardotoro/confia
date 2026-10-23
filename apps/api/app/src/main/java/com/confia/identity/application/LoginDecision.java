package com.confia.identity.application;

import java.time.Duration;
import java.util.Objects;

/**
 * The answer of {@link LogIn}, and the wait the caller must honor before responding
 * (session-endpoints-cookie-and-csrf design.md decision 11). The contract of {@link
 * AuthenticationDecision#requiredDelay()} holds unchanged: the delay is counted from the moment this
 * value is returned, with the transaction already committed, and it delays the response without
 * denying it, so it may be positive next to {@link LoginOutcome.Started}.
 */
public record LoginDecision(LoginOutcome outcome, Duration requiredDelay) {

    public LoginDecision {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(requiredDelay, "requiredDelay");
        if (requiredDelay.isNegative()) {
            throw new IllegalArgumentException("requiredDelay must not be negative, was "
                    + requiredDelay);
        }
    }
}
