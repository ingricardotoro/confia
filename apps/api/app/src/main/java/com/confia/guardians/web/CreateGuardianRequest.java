package com.confia.guardians.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /api/v1/guardians}. The code is not a member: the server generates P-000001, P-000002...
 * The national identity document, the RTN, the e-mail, the phone, the address and the channel are optional. An
 * unknown member, such as an institution, is refused by the JSON mapper.
 */
public record CreateGuardianRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 100, description = "1 to 100 characters once trimmed")
        @NotBlank @Size(max = 300) String firstName,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 100, description = "1 to 100 characters once trimmed")
        @NotBlank @Size(max = 300) String lastName,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, maxLength = 20, pattern = "^[A-Za-z0-9-]{8,20}$",
                description = "National identity document: 8 to 20 letters, digits or hyphens. Stored encrypted; "
                        + "never returned")
        @Size(max = 40) @Pattern(regexp = "\\s*([A-Za-z0-9-]{8,20})?\\s*") String nationalId,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, pattern = "^[0-9]{4}-?[0-9]{4}-?[0-9]{6}$",
                description = "RTN: 14 digits, hyphens optional. Stored encrypted; never returned")
        @Size(max = 40) @Pattern(regexp = "\\s*([0-9]{4}-?[0-9]{4}-?[0-9]{6})?\\s*") String rtn,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, maxLength = 254)
        @Size(max = 300) @Pattern(regexp = "\\s*([^@\\s]+@[^@\\s]+\\.[^@\\s]+)?\\s*") String email,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, maxLength = 20,
                description = "An optional plus and 7 to 20 characters: digits, spaces, parentheses and hyphens")
        @Size(max = 40) @Pattern(regexp = "\\s*(\\+?[0-9(][0-9 ()\\-]{5,18}[0-9])?\\s*") String phone,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, maxLength = 300)
        @Size(max = 400) String address,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, allowableValues = {"EMAIL", "SMS", "WHATSAPP", "NONE"},
                description = "NONE when missing")
        @Pattern(regexp = "EMAIL|SMS|WHATSAPP|NONE") String preferredChannel) {

    /** Personal data never reaches a log through this method. */
    @Override
    public String toString() {
        return "CreateGuardianRequest[REDACTED]";
    }
}
