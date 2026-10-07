package com.confia.shared.security;

import java.util.Optional;

/**
 * How a staff member proved who they are in a session, as the {@code amr} claim of the access token
 * names it (RFC 8176): the password, and the one-time password of the second factor
 * (session-tokens-and-web-layer design.md, decisions 4 and 5). The declaration order is the order the
 * token writes them in, so {@code PASSWORD} always comes first.
 */
public enum AuthenticationMethod {

    PASSWORD("pwd"),
    ONE_TIME_PASSWORD("otp");

    private final String claimValue;

    AuthenticationMethod(String claimValue) {
        this.claimValue = claimValue;
    }

    /** The text of this method in the {@code amr} claim. */
    public String claimValue() {
        return claimValue;
    }

    /** The method an {@code amr} entry names, exactly and in lower case, or empty. */
    public static Optional<AuthenticationMethod> fromClaimValue(String claimValue) {
        for (AuthenticationMethod method : values()) {
            if (method.claimValue.equals(claimValue)) {
                return Optional.of(method);
            }
        }
        return Optional.empty();
    }
}
