package com.confia.identity.application;

import java.util.Objects;

/**
 * The three ways a login ends (session-endpoints-cookie-and-csrf design.md decision 11). It does
 * not say why a login failed: the reason (a wrong password, an identifier that is not an account)
 * goes to the audit trail and nowhere else, so nothing built from this type can tell the two apart.
 */
public sealed interface LoginOutcome {

    /** The password was right and no second factor is owed: a session exists. */
    record Started(SessionGrant session) implements LoginOutcome {

        public Started {
            Objects.requireNonNull(session, "session");
        }
    }

    /** The password was right and a second factor is owed: no session exists. */
    record ChallengeIssued(SecondFactorChallenge challenge) implements LoginOutcome {

        public ChallengeIssued {
            Objects.requireNonNull(challenge, "challenge");
        }
    }

    /** The credentials were not accepted. */
    record Failed() implements LoginOutcome {
    }
}
