package com.confia.students.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An enrollment as the web layer may see it: with the code and the names of the student and the year, the
 * section, the grade and the modality it is in. The document of the student is not here, nor can it be.
 *
 * @param transfers the history of sections, oldest first; {@code null} in a list, where it is not read
 */
public record EnrollmentView(UUID id, UUID studentId, String studentCode, String studentFirstName,
        String studentLastName, UUID academicYearId, String academicYearName, UUID sectionId, String sectionName,
        UUID gradeId, String gradeName, String modalityName, LocalDate enrolledOn, String status,
        LocalDate withdrawnOn, String withdrawalReason, Instant createdAt, Instant updatedAt,
        List<TransferView> transfers) {

    /** Personal data of a minor never reaches a log through this method. */
    @Override
    public String toString() {
        return "EnrollmentView[id=" + id + "]";
    }
}
