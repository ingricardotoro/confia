package com.confia.identity.application;

/**
 * Why a session family is revoked (session-tokens-and-web-layer design.md decision 12, widened by
 * session-endpoints-cookie-and-csrf design.md decision 16): the catalogue of the column {@code
 * identity_refresh_token_family.revoked_reason}, whose check constraint admits exactly these words
 * since {@code V9}. {@link #code()} is the exact text stored there and written to the audit entry.
 * {@code RevocationReasonCatalogIT} reads the constraint from PostgreSQL and fails the build if this
 * enum and the constraint ever differ. {@code admin_revoke} has no producer and is not a reason.
 *
 * <p>{@link #PASSWORD_CHANGE} is requested through {@link AccountSessionRevoker}, which revokes every
 * live family of an account. {@link #REUSE_DETECTED} is decided by the rotation and revokes one family
 * through {@link RefreshTokenFamilyRepository#revokeForReuse}; it is never a reason for {@link
 * AccountSessionRevoker}. {@link #LOGOUT}, {@link #MFA_CHANGE} and {@link #BINDING_CHANGED} are the
 * reasons the session endpoints add; each gets its producer in the slice that delivers it.
 */
public enum SessionRevocationReason {

    PASSWORD_CHANGE("password_change"),
    REUSE_DETECTED("reuse_detected"),
    LOGOUT("logout"),
    MFA_CHANGE("mfa_change"),
    BINDING_CHANGED("binding_changed");

    private final String code;

    SessionRevocationReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
