package com.confia.scholarships.application;

import com.confia.kernel.InstitutionId;
import com.confia.scholarships.domain.Discount;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port of the discounts and the concepts they cover. It inserts, reads and updates the editable columns of a discount under
 * the version check: no operation deletes, and the database refuses a delete to every role.
 */
public interface DiscountRepository {

    /** @throws com.confia.scholarships.domain.DuplicateDiscountCodeException when the code is in use, whatever its capitals */
    void insert(InstitutionId institution, Discount discount);

    Optional<Discount> findById(InstitutionId institution, UUID id);

    /** Reads the discount and takes {@code FOR UPDATE}, so that two edits of it take turns. */
    Optional<Discount> lockById(InstitutionId institution, UUID id);

    /** The discounts with these identifiers, by identifier, in one query; unknown ones are absent. */
    Map<UUID, Discount> findAll(InstitutionId institution, Collection<UUID> ids);

    /**
     * Writes the new name, benefit, validity, stackable flag, active flag and version, and the new set of covered concepts, of a
     * discount that the caller holds locked.
     *
     * @throws com.confia.scholarships.domain.StaleVersionException when the stored version is not {@code expectedVersion}
     * @throws com.confia.scholarships.domain.DiscountBenefitLockedException when the database refuses the change of a benefit in use
     * @throws com.confia.scholarships.domain.DiscountScopeLockedException when the database refuses to widen a discount in use
     * @throws com.confia.scholarships.domain.InvalidScholarshipsValueException when the new validity leaves an assignment outside it
     */
    void replace(InstitutionId institution, Discount after, long expectedVersion);

    Page<Discount> list(InstitutionId institution, DiscountFilter filter, PageRequest page);
}
