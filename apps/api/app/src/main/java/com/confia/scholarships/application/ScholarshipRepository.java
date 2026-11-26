package com.confia.scholarships.application;

import com.confia.kernel.InstitutionId;
import com.confia.scholarships.domain.Scholarship;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port of the scholarships and the concepts they cover. It inserts, reads and updates the editable columns of a scholarship
 * under the version check: no operation deletes, and the database refuses a delete to every role.
 */
public interface ScholarshipRepository {

    /** @throws com.confia.scholarships.domain.DuplicateScholarshipCodeException when the code is in use, whatever its capitals */
    void insert(InstitutionId institution, Scholarship scholarship);

    Optional<Scholarship> findById(InstitutionId institution, UUID id);

    /** Reads the scholarship and takes {@code FOR UPDATE}, so that two edits of it take turns. */
    Optional<Scholarship> lockById(InstitutionId institution, UUID id);

    /** The scholarships with these identifiers, by identifier, in one query; unknown ones are absent. */
    Map<UUID, Scholarship> findAll(InstitutionId institution, Collection<UUID> ids);

    /**
     * Writes the new name, benefit, cap, active flag and version, and the new set of covered concepts, of a scholarship that the
     * caller holds locked.
     *
     * @throws com.confia.scholarships.domain.StaleVersionException when the stored version is not {@code expectedVersion}
     * @throws com.confia.scholarships.domain.ScholarshipBenefitLockedException when the database refuses the change of a benefit
     *         that has approved awards
     */
    void replace(InstitutionId institution, Scholarship after, long expectedVersion);

    Page<Scholarship> list(InstitutionId institution, ScholarshipFilter filter, PageRequest page);
}
