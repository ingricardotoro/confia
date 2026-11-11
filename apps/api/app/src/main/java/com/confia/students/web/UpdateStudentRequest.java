package com.confia.students.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * The body of {@code PUT /api/v1/students/{id}}: every editable member, replaced as a whole. The code is
 * not a member: it never changes. The national identity document is the one member that may be omitted
 * (the stored one is kept); an empty text removes it and any other value replaces it.
 */
public record UpdateStudentRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 100, description = "1 to 100 characters once trimmed")
        @NotBlank @Size(max = 300) String firstName,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 100, description = "1 to 100 characters once trimmed")
        @NotBlank @Size(max = 300) String lastName,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Not in the future and not before 1990-01-01")
        @NotNull @PastOrPresent @BirthDateFloor LocalDate birthDate,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"F", "M", "UNSPECIFIED"})
        @NotNull @Pattern(regexp = "F|M|UNSPECIFIED") String sex,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"ACTIVE", "INACTIVE"},
                description = "INACTIVE takes the student out of use; nothing is deleted")
        @NotNull @Pattern(regexp = "ACTIVE|INACTIVE") String status,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, maxLength = 20, pattern = "^[A-Za-z0-9-]{8,20}$",
                description = "Omitted keeps the stored document; an empty text removes it; a document of 8 to 20 "
                        + "letters, digits or hyphens replaces it. Never returned")
        @Size(max = 40) @Pattern(regexp = "\\s*([A-Za-z0-9-]{8,20})?\\s*") String nationalId) {

    /** Personal data of a minor never reaches a log through this method. */
    @Override
    public String toString() {
        return "UpdateStudentRequest[REDACTED]";
    }
}
