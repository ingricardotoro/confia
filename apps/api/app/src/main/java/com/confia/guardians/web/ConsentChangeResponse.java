package com.confia.guardians.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;

/**
 * The answer to a consent change: the standing of the combination afterwards and whether an event was written. A
 * repeat of the action that already is the current state answers the same standing with {@code changed} false.
 */
public record ConsentChangeResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"EMAIL", "SMS", "WHATSAPP"}) String channel,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"INSTITUTIONAL", "MARKETING"})
        String purpose,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"GRANTED", "WITHDRAWN", "NEVER_GRANTED"})
        String state,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) Instant since,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String policyVersion,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                allowableValues = {"SIGNED_PAPER_FORM", "ADMIN_REGISTERED", "PORTAL_FORM"}) String evidenceSource,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "True when an event was written; false when the request repeated the current state")
        boolean changed) {
}
