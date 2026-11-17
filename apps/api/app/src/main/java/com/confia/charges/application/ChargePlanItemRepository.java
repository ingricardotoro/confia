package com.confia.charges.application;

import com.confia.charges.domain.ChargePlanItem;
import com.confia.kernel.InstitutionId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port of the items of a charge plan. An item is inserted, read and, while its plan is a draft, deleted; it is
 * never updated. The database refuses to change the items of a plan that is not a draft.
 */
public interface ChargePlanItemRepository {

    /** The items of the plan, by creation. */
    List<ChargePlanItem> findByPlan(InstitutionId institutionId, UUID planId);

    Optional<ChargePlanItem> find(InstitutionId institutionId, UUID planId, UUID itemId);

    long countByPlan(InstitutionId institutionId, UUID planId);

    /**
     * Inserts an item.
     *
     * @throws com.confia.charges.domain.DuplicatePlanItemException when the plan already has the concept
     */
    void insert(InstitutionId institutionId, ChargePlanItem item);

    /** Deletes the item of the plan; whether a row was deleted. */
    boolean delete(InstitutionId institutionId, UUID planId, UUID itemId);
}
