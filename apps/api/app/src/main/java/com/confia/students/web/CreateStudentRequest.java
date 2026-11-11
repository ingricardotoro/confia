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
 * The body of {@code POST /api/v1/students}. The code is optional: when it is missing or blank the server
 * generates E-000001, E-000002... The national identity document is optional. An unknown member, such as
 * an institution, is refused by the JSON mapper.
 */
public record CreateStudentRequest(
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, maxLength = 30,
                description = "Student code, unique in the institution; generated when missing")
        @Size(max = 30) String studentCode,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 100, description = "1 to 100 characters once trimmed")
        @NotBlank @Size(max = 300) String firstName,
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 100, description = "1 to 100 characters once trimmed")
        @NotBlank @Size(max = 300) String lastName,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "Not in the future and not before 1990-01-01")
        @NotNull @PastOrPresent @BirthDateFloor LocalDate birthDate,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"F", "M", "UNSPECIFIED"})
        @NotNull @Pattern(regexp = "F|M|UNSPECIFIED") String sex,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, maxLength = 20, pattern = "^[A-Za-z0-9-]{8,20}$",
                description = "National identity document: 8 to 20 letters, digits or hyphens. Stored encrypted; "
                        + "never returned")
        @Size(max = 40) @Pattern(regexp = "\\s*([A-Za-z0-9-]{8,20})?\\s*") String nationalId) {

    /** Personal data of a minor never reaches a log through this method. */
    @Override
    public String toString() {
        return "CreateStudentRequest[REDACTED]";
    }
}
