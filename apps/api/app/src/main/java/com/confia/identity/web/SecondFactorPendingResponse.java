package com.confia.identity.web;

import com.confia.identity.application.SecondFactorChallenge;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.Objects;

/**
 * The answer of a login whose password was right and whose second factor is still owed
 * (session-endpoints-cookie-and-csrf design.md decision 11): the restricted token and what it is
 * for. No session exists behind it, no cookie goes with it, and the token opens only the routes of
 * the second factor.
 *
 * <p>{@code toString()} redacts the token, which a record would print.
 *
 * @param status {@code second-factor-required} when the account has a verified second factor and
 *     {@code second-factor-enrollment-required} when it has none
 * @param secondFactorToken the compact restricted token
 * @param secondFactorTokenExpiresAt when the restricted token stops being valid
 */
public record SecondFactorPendingResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED,
                allowableValues = {"second-factor-required", "second-factor-enrollment-required"},
                description = "Whether the account must present a code or must enroll a second factor")
        String status,
        @Schema(requiredMode = RequiredMode.REQUIRED, format = "password",
                description = "The restricted token, for the routes of the second factor only")
        String secondFactorToken,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "When the restricted token stops being valid")
        Instant secondFactorTokenExpiresAt) implements LoginResponse {

    static final String VERIFICATION_REQUIRED = "second-factor-required";
    static final String ENROLLMENT_REQUIRED = "second-factor-enrollment-required";

    public SecondFactorPendingResponse {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(secondFactorToken, "secondFactorToken");
        Objects.requireNonNull(secondFactorTokenExpiresAt, "secondFactorTokenExpiresAt");
    }

    /** The answer for the challenge the use case issued. */
    static SecondFactorPendingResponse of(SecondFactorChallenge challenge) {
        String status = switch (challenge.kind()) {
            case VERIFICATION_REQUIRED -> VERIFICATION_REQUIRED;
            case ENROLLMENT_REQUIRED -> ENROLLMENT_REQUIRED;
        };
        return new SecondFactorPendingResponse(status, challenge.token(), challenge.expiresAt());
    }

    @Override
    public String toString() {
        return "SecondFactorPendingResponse[status=" + status + ", secondFactorToken=REDACTED]";
    }
}
