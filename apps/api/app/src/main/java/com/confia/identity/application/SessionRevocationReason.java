package com.confia.identity.application;

/**
 * Why a session family is revoked on purpose by this change (session-tokens-and-web-layer design.md
 * decision 12). The column {@code identity_refresh_token_family.revoked_reason} has a closed
 * catalogue; this enum carries the reasons the application layer asks for, and {@link #code()} is
 * the exact text stored there and written to the audit entry. Reuse detection ({@code
 * reuse_detected}) is decided by the rotation, not requested through {@link AccountSessionRevoker}.
 */
public enum SessionRevocationReason {

    PASSWORD_CHANGE("password_change");

    private final String code;

    SessionRevocationReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
