package com.confia.guardians.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code PUT /api/v1/guardians/{id}}: every editable member, replaced as a whole (an omitted or empty
 * e-mail, phone or address clears it). The code is not a member: it never changes. The national identity document
 * and the RTN are the two members that may be omitted (the stored one is kept); an empty text removes it and any
 * other value replaces it.
 */
public record UpdateGuardianRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 100, description = "1 to 100 characters once trimmed")
        @NotBlank @Size(max = 300) String firstName,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 100, description = "1 to 100 characters once trimmed")
        @NotBlank @Size(max = 300) String lastName,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, maxLength = 20, pattern = "^[A-Za-z0-9-]{8,20}$",
                description = "Omitted keeps the stored document; an empty text removes it; a document of 8 to 20 "
                        + "letters, digits or hyphens replaces it. Never returned")
        @Size(max = 40) @Pattern(regexp = "\\s*([A-Za-z0-9-]{8,20})?\\s*") String nationalId,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, pattern = "^[0-9]{4}-?[0-9]{4}-?[0-9]{6}$",
                description = "Omitted keeps the stored RTN; an empty text removes it; 14 digits replace it. "
                        + "Never returned")
        @Size(max = 40) @Pattern(regexp = "\\s*([0-9]{4}-?[0-9]{4}-?[0-9]{6})?\\s*") String rtn,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, maxLength = 254,
                description = "Omitted or empty clears it")
        @Size(max = 300) @Pattern(regexp = "\\s*([^@\\s]+@[^@\\s]+\\.[^@\\s]+)?\\s*") String email,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, maxLength = 20, description = "Omitted or empty clears it")
        @Size(max = 40) @Pattern(regexp = "\\s*(\\+?[0-9(][0-9 ()\\-]{5,18}[0-9])?\\s*") String phone,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, maxLength = 300, description = "Omitted or empty clears it")
        @Size(max = 400) String address,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, allowableValues = {"EMAIL", "SMS", "WHATSAPP", "NONE"},
                description = "NONE when missing")
        @Pattern(regexp = "EMAIL|SMS|WHATSAPP|NONE") String preferredChannel,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"ACTIVE", "INACTIVE"},
                description = "INACTIVE takes the guardian out of use and deletes nothing; its current links stay")
        @NotNull @Pattern(regexp = "ACTIVE|INACTIVE") String status) {

    /** Personal data never reaches a log through this method. */
    @Override
    public String toString() {
        return "UpdateGuardianRequest[REDACTED]";
    }
}
