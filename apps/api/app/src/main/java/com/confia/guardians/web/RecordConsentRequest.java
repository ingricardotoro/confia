package com.confia.guardians.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /api/v1/guardians/{id}/consents}: one combination of channel and purpose, the action, the
 * evidence and, to grant, the version of the policy. The time of the event is not a member (the server decides it), nor
 * is the actor (the token). The purpose and the evidence source are checked against their closed sets by the domain,
 * which names the reason (the transactional purpose is {@code not-consentable}, the portal's evidence {@code
 * not-allowed}); an unknown member is refused by the JSON mapper.
 */
public record RecordConsentRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"EMAIL", "SMS", "WHATSAPP"})
        @NotNull @Pattern(regexp = "EMAIL|SMS|WHATSAPP") String channel,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"INSTITUTIONAL", "MARKETING"},
                description = "The transactional purpose (billing, arrears, invoices, security) is not a consent and "
                        + "is refused")
        @NotNull @Size(max = 40) String purpose,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"GRANT", "WITHDRAW"})
        @NotNull @Pattern(regexp = "GRANT|WITHDRAW") String action,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"SIGNED_PAPER_FORM", "ADMIN_REGISTERED"},
                description = "PORTAL_FORM belongs to the portal and is refused here")
        @NotNull @Size(max = 40) String evidenceSource,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, pattern = "^[A-Za-z0-9._-]{1,32}$", maxLength = 32,
                description = "Version of the privacy policy the guardian accepted, for example 2026-01. Required to "
                        + "GRANT, omitted to WITHDRAW")
        @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._-]{1,32}") String policyVersion) {

    /** Spring logs the object it reads at TRACE: whatever a client typed into a member never reaches a log through it. */
    @Override
    public String toString() {
        return "RecordConsentRequest[REDACTED]";
    }
}
