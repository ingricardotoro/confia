package com.confia.scholarships.application;

import com.confia.kernel.InstitutionId;
import com.confia.scholarships.domain.AwardStatus;
import com.confia.scholarships.domain.ScholarshipAward;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Output port of the awards. It inserts, reads and advances the status once along the allowed transition: no operation edits another
 * column or deletes, and the database refuses both to every role.
 */
public interface AwardRepository {

    /**
     * @throws com.confia.scholarships.domain.InvalidScholarshipsValueException when the validity is not inside the academic year
     * @throws com.confia.scholarships.domain.InactiveReferenceException when the scholarship is inactive
     */
    void insert(InstitutionId institution, ScholarshipAward award);

    Optional<ScholarshipAward> findById(InstitutionId institution, UUID id);

    /** Reads the award and takes {@code FOR UPDATE}, so two decisions on it take turns. */
    Optional<ScholarshipAward> findForUpdate(InstitutionId institution, UUID id);

    /**
     * Moves the status forward; the database refuses any other move and one without the decision behind it.
     *
     * @throws com.confia.scholarships.domain.AwardOverlapException when approving makes two approved awards of the scholarship
     *         overlap for the student
     * @throws com.confia.scholarships.domain.InactiveReferenceException when approving an award of an inactive scholarship
     */
    void advanceStatus(InstitutionId institution, UUID id, AwardStatus to);

    /** Of these scholarships, the ones that have an award that was approved (and maybe revoked since). */
    Set<UUID> withApprovedAwards(InstitutionId institution, Collection<UUID> scholarshipIds);

    Page<ScholarshipAward> list(InstitutionId institution, AwardFilter filter, PageRequest page);
}
