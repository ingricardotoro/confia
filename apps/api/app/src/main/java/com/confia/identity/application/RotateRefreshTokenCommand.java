package com.confia.identity.application;

import com.confia.shared.security.ClientAddress;
import java.util.Objects;

/**
 * A refresh token presented for rotation (session-tokens-and-web-layer design.md decision 10, widened
 * by session-endpoints-cookie-and-csrf design.md decision 14 and scenario I205): the text, as the
 * client sent it, the agent of the client as the request filter already sanitized it, the resolved
 * address of the client and the identifier of the request. The agent and the address are what the
 * binding of the session compares with the ones recorded when the family was created; both may be
 * {@code null} for a call that has no HTTP request, and an unknown side never counts as changed. It
 * has no institution field, because the institution comes from the process configuration and never
 * from the caller.
 *
 * <p><b>A final class with a redacted {@code toString()}, never a {@code record}</b>: it carries the
 * clear-text token, the lesson of {@code ResetPasswordCommand}, and the address of the client.
 */
public final class RotateRefreshTokenCommand {

    private final String presentedToken;
    private final String userAgent;
    private final ClientAddress clientAddress;
    private final String requestId;

    /**
     * @param presentedToken the text presented, which may be anything: a text that cannot be an
     *     issued token is a rejection, not an error
     * @param userAgent the sanitized agent text, or {@code null} when there is none
     * @param clientAddress the resolved address of the client, or {@code null} when there is none
     * @param requestId the identifier of the request for the entries of the trail; a blank one gets a
     *     fresh identifier
     */
    public RotateRefreshTokenCommand(String presentedToken, String userAgent,
            ClientAddress clientAddress, String requestId) {
        this.presentedToken = Objects.requireNonNull(presentedToken, "presentedToken");
        this.userAgent = userAgent;
        this.clientAddress = clientAddress;
        this.requestId = Objects.requireNonNull(requestId, "requestId");
    }

    /** A command with no address and no request identifier: the form a caller without a request uses. */
    public RotateRefreshTokenCommand(String presentedToken, String userAgent) {
        this(presentedToken, userAgent, null, "");
    }

    public String presentedToken() {
        return presentedToken;
    }

    public String userAgent() {
        return userAgent;
    }

    public ClientAddress clientAddress() {
        return clientAddress;
    }

    public String requestId() {
        return requestId;
    }

    /** Redacted deliberately: the token is a secret, and the agent and the address are never printed. */
    @Override
    public String toString() {
        return "RotateRefreshTokenCommand[REDACTED]";
    }
}
