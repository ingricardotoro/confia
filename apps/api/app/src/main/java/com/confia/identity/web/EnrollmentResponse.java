package com.confia.identity.web;

import com.confia.identity.application.EnrollTotpSecondFactorResult.Enrolled;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import java.util.Objects;

/**
 * The answer of {@code POST /api/v1/auth/mfa/enrollments} (session-endpoints-cookie-and-csrf design.md,
 * decision 13): the secret in base32 and the ten recovery codes, shown once. They are never written to
 * a log, and this record prints neither.
 *
 * @param secret the TOTP secret in base32, 32 characters without padding
 * @param recoveryCodes the ten recovery codes
 */
public record EnrollmentResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "password", minLength = 32,
                maxLength = 32, pattern = "^[A-Z2-7]{32}$",
                description = "The TOTP secret in base32 without padding, for the authenticator "
                        + "application. Shown once")
        String secret,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "The ten recovery codes. Shown once")
        List<String> recoveryCodes) {

    public EnrollmentResponse {
        Objects.requireNonNull(secret, "secret");
        recoveryCodes = List.copyOf(Objects.requireNonNull(recoveryCodes, "recoveryCodes"));
    }

    /** The answer for the enrollment the use case wrote. */
    static EnrollmentResponse of(Enrolled enrolled) {
        return new EnrollmentResponse(enrolled.secretBase32(), enrolled.recoveryCodeTexts());
    }

    @Override
    public String toString() {
        return "EnrollmentResponse[secret=REDACTED, recoveryCodes=REDACTED]";
    }
}
