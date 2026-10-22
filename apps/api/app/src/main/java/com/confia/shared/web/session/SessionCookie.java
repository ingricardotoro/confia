package com.confia.shared.web.session;

import java.util.Objects;

/**
 * What a request carries as its session cookie (session-endpoints-cookie-and-csrf design.md,
 * decision 5): none, exactly one, or more than one. More than one is never resolved by choosing
 * one: the CSRF filter refuses it and the refresh treats it as a rejection.
 */
public sealed interface SessionCookie {

    /** The request carries no session cookie. */
    record Absent() implements SessionCookie {
    }

    /**
     * The request carries exactly one session cookie. The text is the refresh token, a secret, so it
     * is never printed. It may be empty: an empty cookie is a cookie, and it is the use case that
     * rejects it, the same way the CSRF filter treats any session cookie as present.
     *
     * @param text the value of the cookie
     */
    record Present(String text) implements SessionCookie {

        public Present {
            Objects.requireNonNull(text, "text");
        }

        @Override
        public String toString() {
            return "Present[text=<redacted>]";
        }
    }

    /** The request carries two or more session cookies. */
    record Duplicated() implements SessionCookie {
    }
}
