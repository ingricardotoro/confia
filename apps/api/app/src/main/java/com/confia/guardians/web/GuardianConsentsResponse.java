package com.confia.guardians.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import java.util.UUID;

/** The consents of a guardian: the standing of the six combinations and the newest events first (at most 100). */
public record GuardianConsentsResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID guardianId,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Always the six combinations, channel by channel (EMAIL, SMS, WHATSAPP) and, inside "
                        + "each, INSTITUTIONAL before MARKETING")
        List<ConsentStandingResponse> states,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 100,
                description = "The newest events first, at most 100") List<ConsentEventResponse> history) {
}
