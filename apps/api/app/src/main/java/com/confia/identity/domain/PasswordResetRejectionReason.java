package com.confia.identity.domain;

/**
 * Why a password reset was rejected, with the code its audit entry carries (password-recovery-token
 * design.md decisions 4 and 9). These are internal: the caller only ever sees one uniform rejection
 * for every token reason. {@link PasswordResetTokenPolicy#rejectionReasonOf} returns the first three
 * token reasons in their precedence order; the use case decides the others.
 */
public enum PasswordResetRejectionReason {

    TOKEN_NOT_FOUND("token-not-found"),
    TOKEN_EXPIRED("token-expired"),
    TOKEN_SUPERSEDED("token-superseded"),
    TOKEN_CONSUMED("token-consumed"),
    PASSWORD_TOO_SHORT("password-too-short"),
    PASSWORD_TOO_LONG("password-too-long"),
    SECOND_FACTOR_MISSING("second-factor-missing"),
    SECOND_FACTOR_INVALID("second-factor-invalid");

    private final String auditCode;

    PasswordResetRejectionReason(String auditCode) {
        this.auditCode = auditCode;
    }

    /** The value of {@code reason} in the {@code identity.password_reset.rejected} audit entry. */
    public String auditCode() {
        return auditCode;
    }
}
