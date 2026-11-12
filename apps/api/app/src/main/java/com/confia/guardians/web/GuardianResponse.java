package com.confia.guardians.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A guardian, as the API answers it. The national identity document and the RTN are never members: only whether
 * there is one of each. The linked students are present in the detail and in the answers of the writes, and absent
 * in a list; a guardian that is {@code INACTIVE} and still lists students has current links that stay.
 */
public record GuardianResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "P-000001, P-000002... unique in the institution")
        String guardianCode,
        @Schema(requiredMode = RequiredMode.REQUIRED) String firstName,
        @Schema(requiredMode = RequiredMode.REQUIRED) String lastName,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Whether the guardian has a national identity document")
        boolean hasNationalId,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Whether the guardian has an RTN") boolean hasRtn,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String email,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String phone,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String address,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"EMAIL", "SMS", "WHATSAPP", "NONE"})
        String preferredChannel,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"ACTIVE", "INACTIVE"}) String status,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED,
                description = "The students the guardian is linked to now; absent in a list")
        @JsonInclude(JsonInclude.Include.NON_NULL) List<LinkedStudentResponse> students) {

    /** Spring logs the object it writes at DEBUG: the contact data never reaches a log through it. */
    @Override
    public String toString() {
        return "GuardianResponse[id=" + id + "]";
    }
}
