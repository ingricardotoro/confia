package com.confia.identity.domain;

import java.util.Objects;

/**
 * Why a refresh token was not rotated (session-tokens-and-web-layer design.md decision 10; the
 * closed vocabulary of the rejected outcome). The codes are those of the audit entry's {@code
 * afterValue} (decision 15), so a reader of the trail and a reader of the code see the same words.
 */
public enum RefreshRejection {

    /** The text is not a token this system issued, or no row has its hash. */
    UNKNOWN_TOKEN("unknown_token"),
    /** The family was revoked, by a password change or by a confirmed reuse. */
    FAMILY_REVOKED("family_revoked"),
    /** The family reached its absolute end or went 30 minutes without use. */
    FAMILY_EXPIRED("family_expired"),
    /** The token reached its own expiry while its family was alive. */
    TOKEN_EXPIRED("token_expired"),
    /** A consumed token was presented outside the grace window. */
    REUSE_DETECTED("reuse_detected");

    private final String auditCode;

    RefreshRejection(String auditCode) {
        this.auditCode = Objects.requireNonNull(auditCode, "auditCode");
    }

    /** The snake-case word the audit entry carries for this reason. */
    public String auditCode() {
        return auditCode;
    }
}
