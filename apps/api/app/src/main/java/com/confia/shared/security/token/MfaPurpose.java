package com.confia.shared.security.token;

import java.util.Optional;

/**
 * What a restricted MFA token may be used for: the closed set of the {@code purpose} claim
 * (session-tokens-and-web-layer design.md, decision 4). Any other text, a missing claim or a different
 * letter case is not a purpose, and the token that carries it is invalid.
 */
public enum MfaPurpose {

    MFA_VERIFY("mfa-verify"),
    MFA_ENROLL("mfa-enroll");

    private final String claimValue;

    MfaPurpose(String claimValue) {
        this.claimValue = claimValue;
    }

    /** The text of this purpose in the {@code purpose} claim. */
    public String claimValue() {
        return claimValue;
    }

    /** The purpose a {@code purpose} claim names, exactly, or empty. */
    public static Optional<MfaPurpose> fromClaimValue(String claimValue) {
        for (MfaPurpose purpose : values()) {
            if (purpose.claimValue.equals(claimValue)) {
                return Optional.of(purpose);
            }
        }
        return Optional.empty();
    }
}
