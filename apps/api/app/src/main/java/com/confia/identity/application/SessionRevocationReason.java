package com.confia.identity.application;

/**
 * Why a session family is revoked (session-tokens-and-web-layer design.md decision 12): the catalogue
 * of the column {@code identity_refresh_token_family.revoked_reason}, whose V8 check constraint admits
 * exactly these words. {@link #code()} is the exact text stored there and written to the audit
 * entry. {@code RevocationReasonCatalogIT} reads the constraint from PostgreSQL and fails the build if
 * this enum and the constraint ever differ.
 *
 * <p>{@link #PASSWORD_CHANGE} is requested through {@link AccountSessionRevoker}, which revokes every
 * live family of an account. {@link #REUSE_DETECTED} is decided by the rotation and revokes one family
 * through {@link RefreshTokenFamilyRepository#revokeForReuse}; it is never a reason for {@link
 * AccountSessionRevoker}.
 */
public enum SessionRevocationReason {

    PASSWORD_CHANGE("password_change"),
    REUSE_DETECTED("reuse_detected");

    private final String code;

    SessionRevocationReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
