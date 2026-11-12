package com.confia.guardians.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/** One recorded consent event of the history. It carries no contact data and no staff account. */
public record ConsentEventResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"EMAIL", "SMS", "WHATSAPP"}) String channel,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"INSTITUTIONAL", "MARKETING"})
        String purpose,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"GRANT", "WITHDRAW"}) String action,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                allowableValues = {"SIGNED_PAPER_FORM", "ADMIN_REGISTERED", "PORTAL_FORM"}) String evidenceSource,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                description = "The policy version of a grant; null for a withdrawal") String policyVersion,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Server time of the event") Instant occurredAt) {
}
