package com.confia.guardians.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;

/**
 * The current standing of one combination of channel and purpose, derived from its latest event. A combination
 * without events is {@code NEVER_GRANTED} and carries no instant, version or evidence; nothing is ever pre-marked.
 */
public record ConsentStandingResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"EMAIL", "SMS", "WHATSAPP"}) String channel,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"INSTITUTIONAL", "MARKETING"})
        String purpose,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"GRANTED", "WITHDRAWN", "NEVER_GRANTED"})
        String state,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                description = "When the latest event happened; null when never granted") Instant since,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                description = "The policy version of the grant; null when withdrawn or never granted")
        String policyVersion,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                allowableValues = {"SIGNED_PAPER_FORM", "ADMIN_REGISTERED", "PORTAL_FORM"},
                description = "Evidence of the latest event; null when never granted") String evidenceSource) {
}
