package com.confia.identity.application;

import com.confia.shared.security.ClientAddress;
import java.util.Objects;

/**
 * What {@link LogIn} needs from its caller (session-endpoints-cookie-and-csrf design.md decision
 * 11): the credentials as presented and the origin of the request. The institution is not a field,
 * because a login never reads it from the request: it comes from the configuration of the process.
 *
 * @param identifier the identifier as typed
 * @param password the password as typed
 * @param clientAddress the resolved address of the client, or {@code null} for a call without a
 *     request
 * @param userAgent the agent text the request filter already sanitized, or {@code null}
 * @param requestId the identifier of the request, for the entries; a blank one gets a fresh
 *     identifier
 */
public record LoginCommand(String identifier, String password, ClientAddress clientAddress,
        String userAgent, String requestId) {

    public LoginCommand {
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(requestId, "requestId");
    }

    /**
     * Redacts the password, and the address and the agent with it: the compiler-generated {@code
     * toString()} of a record prints every component. The identifier stays visible, as in {@link
     * AuthenticationCommand}.
     */
    @Override
    public String toString() {
        return "LoginCommand[identifier=" + identifier + ", password=REDACTED, clientAddress=REDACTED,"
                + " userAgent=REDACTED, requestId=" + requestId + "]";
    }
}
