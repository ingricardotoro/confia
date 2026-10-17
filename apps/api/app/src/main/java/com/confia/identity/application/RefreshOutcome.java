package com.confia.identity.application;

import com.confia.identity.domain.RefreshRejection;
import java.util.Objects;

/**
 * The closed result of presenting a refresh token (session-tokens-and-web-layer design.md decision
 * 10): the session rotated, or a reason it was not. Every rejection is a value and never an
 * exception, so the transaction commits what the rejection wrote (a confirmed reuse revokes the
 * family and the revocation must survive the response). The endpoint that translates a rejection into
 * one uniform response is {@code session-endpoints-cookie-and-csrf}'s.
 */
public sealed interface RefreshOutcome {

    /** How a rotated session came about: a normal rotation, or a retry inside the grace window. */
    enum RotationKind {
        FRESH,
        GRACE
    }

    /** The token rotated; the session carries the successor token and a new access token. */
    record Rotated(IssuedSession session, RotationKind kind) implements RefreshOutcome {

        public Rotated {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** The token did not rotate, for exactly one of the reasons of {@link RefreshRejection}. */
    record Rejected(RefreshRejection reason) implements RefreshOutcome {

        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
