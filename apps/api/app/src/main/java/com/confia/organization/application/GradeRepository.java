package com.confia.organization.application;

import com.confia.kernel.InstitutionId;
import com.confia.organization.domain.Grade;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port of the grades of an institution. A name or an ordinal already taken in the modality is
 * refused by {@link #insert} and {@link #update} as a {@code DuplicateNameException}.
 */
public interface GradeRepository {

    Optional<Grade> findById(InstitutionId institutionId, UUID id);

    void insert(InstitutionId institutionId, Grade grade);

    void update(InstitutionId institutionId, Grade grade);

    /** @param modalityId only the grades of this modality, or {@code null} for all of them */
    Page<Grade> list(InstitutionId institutionId, UUID modalityId, PageRequest request);
}
