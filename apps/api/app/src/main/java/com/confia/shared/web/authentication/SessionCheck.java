package com.confia.shared.web.authentication;

/**
 * What an authenticated route asks of the session behind a verified token
 * (session-tokens-and-web-layer design.md, decisions 5 and 7). {@link #TOKEN_ONLY} accepts any token
 * whose signature and claims are good, for as long as it lives. {@link #LIVE_SESSION} also requires
 * the session itself to be alive, which the sensitive writes ask for; the query of the session port
 * arrives with task 5.1, and until it does a route that declares it is refused rather than trusted.
 */
public enum SessionCheck {

    TOKEN_ONLY,
    LIVE_SESSION
}
