package com.confia.scholarships.application;

import com.confia.kernel.InstitutionId;
import com.confia.scholarships.domain.AwardStatus;
import com.confia.scholarships.domain.DiscountAssignment;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Output port of the discount assignments. It inserts, reads and advances the status once along the allowed transition: no
 * operation edits another column or deletes, and the database refuses both to every role.
 */
public interface DiscountAssignmentRepository {

    /**
     * @throws com.confia.scholarships.domain.InvalidScholarshipsValueException when the discount is not of agreement kind or the
     *         validity is not inside the one of the discount
     * @throws com.confia.scholarships.domain.InactiveReferenceException when the discount is inactive
     */
    void insert(InstitutionId institution, DiscountAssignment assignment);

    Optional<DiscountAssignment> findById(InstitutionId institution, UUID id);

    /** Reads the assignment and takes {@code FOR UPDATE}, so two decisions on it take turns. */
    Optional<DiscountAssignment> findForUpdate(InstitutionId institution, UUID id);

    /**
     * Moves the status forward; the database refuses any other move and one without the decision behind it.
     *
     * @throws com.confia.scholarships.domain.DiscountAssignmentOverlapException when approving makes two approved assignments of the
     *         discount overlap for the student
     * @throws com.confia.scholarships.domain.InactiveReferenceException when approving an assignment of an inactive discount
     */
    void advanceStatus(InstitutionId institution, UUID id, AwardStatus to);

    /**
     * Of these discounts, the ones in use: they have an assignment that was approved (and maybe revoked since). The accrual task adds
     * the discounts that a charge was issued with.
     */
    Set<UUID> inUse(InstitutionId institution, Collection<UUID> discountIds);

    Page<DiscountAssignment> list(InstitutionId institution, AssignmentFilter filter, PageRequest page);
}
