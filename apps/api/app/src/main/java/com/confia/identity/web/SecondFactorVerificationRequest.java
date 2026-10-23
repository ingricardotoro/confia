package com.confia.identity.web;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /api/v1/auth/mfa/verifications} (session-endpoints-cookie-and-csrf design.md,
 * decision 13): exactly one proof, a TOTP code or a recovery code. The edge checks that there is one
 * and bounds its length; what a text may be, and whether it is right, is the use case's decision, so
 * the answer does not tell a malformed code from a wrong one. Both print redacted.
 *
 * @param code the TOTP code of the authenticator application
 * @param recoveryCode one of the recovery codes of the enrollment
 */
public record SecondFactorVerificationRequest(
        @Schema(maxLength = 64, description = "The current code of the authenticator application. "
                + "Exactly one of code and recoveryCode is sent")
        @Size(max = 64) String code,
        @Schema(maxLength = 64, description = "A recovery code of the enrollment. Exactly one of "
                + "code and recoveryCode is sent; the first verification of a credential does not "
                + "accept it")
        @Size(max = 64) String recoveryCode) {

    /** Whether exactly one proof is present; the bean validation of the body reports it as a field error. */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "exactly one of code and recoveryCode is required")
    public boolean isExactlyOneProof() {
        return (code == null || code.isBlank()) != (recoveryCode == null || recoveryCode.isBlank());
    }

    @Override
    public String toString() {
        return "SecondFactorVerificationRequest[code=REDACTED, recoveryCode=REDACTED]";
    }
}
