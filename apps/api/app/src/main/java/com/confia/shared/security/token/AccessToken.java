package com.confia.shared.security.token;

import java.time.Instant;
import java.util.Objects;

/**
 * A token the issuer signed, ready to be handed to a client: the compact text and the instant it stops
 * being valid. Printing it shows the instant and never the text or any piece of it, so that a token
 * cannot reach a log through string concatenation or a formatted message (CLAUDE.md, regla 11).
 *
 * @param compact the compact JWS, the only place the token is ever written in full
 * @param expiresAt when the token stops being valid
 */
public record AccessToken(String compact, Instant expiresAt) {

    public AccessToken {
        Objects.requireNonNull(compact, "compact");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    @Override
    public String toString() {
        return "AccessToken[compact=<redacted>, expiresAt=" + expiresAt + "]";
    }
}
