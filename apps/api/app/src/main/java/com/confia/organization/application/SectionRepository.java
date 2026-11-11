package com.confia.organization.application;

import com.confia.kernel.InstitutionId;
import com.confia.organization.domain.Section;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port of the sections of an institution. A name already taken in the year and grade is
 * refused by {@link #insert} and {@link #update} as a {@code DuplicateNameException}.
 */
public interface SectionRepository {

    Optional<Section> findById(InstitutionId institutionId, UUID id);

    void insert(InstitutionId institutionId, Section section);

    void update(InstitutionId institutionId, Section section);

    /**
     * @param academicYearId only the sections of this year, or {@code null}
     * @param gradeId only the sections of this grade, or {@code null}
     */
    Page<Section> list(InstitutionId institutionId, UUID academicYearId, UUID gradeId,
            PageRequest request);
}
