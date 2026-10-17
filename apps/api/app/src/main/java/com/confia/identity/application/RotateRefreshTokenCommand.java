package com.confia.identity.application;

import java.util.Objects;

/**
 * A refresh token presented for rotation (session-tokens-and-web-layer design.md decision 10): the
 * text, as the client sent it, and the agent of the client as the request filter already sanitized
 * it. It has no institution field, because the institution comes from the process configuration and
 * never from the caller, and no address, because nothing is decided on it: the origin of the entry
 * is completed by the request decorator of the audit writer.
 *
 * <p><b>A final class with a redacted {@code toString()}, never a {@code record}</b>: it carries the
 * clear-text token, the lesson of {@code ResetPasswordCommand}.
 */
public final class RotateRefreshTokenCommand {

    private final String presentedToken;
    private final String userAgent;

    /**
     * @param presentedToken the text presented, which may be anything: a text that cannot be an
     *     issued token is a rejection, not an error
     * @param userAgent the sanitized agent text, or {@code null} when there is none
     */
    public RotateRefreshTokenCommand(String presentedToken, String userAgent) {
        this.presentedToken = Objects.requireNonNull(presentedToken, "presentedToken");
        this.userAgent = userAgent;
    }

    public String presentedToken() {
        return presentedToken;
    }

    public String userAgent() {
        return userAgent;
    }

    /** Redacted deliberately: the token is a secret, and the agent is only ever stored as a digest. */
    @Override
    public String toString() {
        return "RotateRefreshTokenCommand[REDACTED]";
    }
}
