package com.confia.organization.application.directory;

import com.confia.kernel.InstitutionId;
import java.util.UUID;

/**
 * Output port, read-only, through which other modules ask {@code organization} whether a piece of the academic
 * structure exists in an institution. Every method is scoped to the institution given: a row of another institution is
 * never found, so it looks the same as a row that does not exist. It answers nothing else about the row.
 */
public interface AcademicStructureDirectory {

    /** Whether the academic year exists in the institution. */
    boolean academicYearExists(InstitutionId institutionId, UUID academicYearId);

    /** Whether the modality exists in the institution. */
    boolean modalityExists(InstitutionId institutionId, UUID modalityId);

    /** Whether the grade exists in the institution. */
    boolean gradeExists(InstitutionId institutionId, UUID gradeId);

    /** Whether the section exists in the institution. */
    boolean sectionExists(InstitutionId institutionId, UUID sectionId);
}
