package com.confia.identity.application;

import java.util.Objects;

/**
 * How the completion of a second factor ended (session-endpoints-cookie-and-csrf design.md, decision
 * 13): a session exists, or the proof was not accepted. The refusal says nothing about why: a wrong
 * code, a code already used, a recovery code that this purpose does not accept and a credential in
 * another state are one answer, so the edge cannot become an oracle for the state of the credential.
 */
public sealed interface SecondFactorOutcome {

    /** The proof was accepted and a session was opened. */
    record Started(SessionGrant session) implements SecondFactorOutcome {

        public Started {
            Objects.requireNonNull(session, "session");
        }
    }

    /** The proof was not accepted and nothing was opened. */
    record Rejected() implements SecondFactorOutcome {
    }
}
