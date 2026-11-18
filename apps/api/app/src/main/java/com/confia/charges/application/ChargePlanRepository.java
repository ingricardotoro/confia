package com.confia.charges.application;

import com.confia.charges.domain.ChargePlan;
import com.confia.kernel.InstitutionId;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port of the charge plans of an institution. There is no delete: a plan is archived. The year, the target and the
 * creation of a plan are written once, by {@link #insert}; {@link #update} writes the name, the frequency, the due day,
 * the status and the version.
 */
public interface ChargePlanRepository {

    Optional<ChargePlan> findById(InstitutionId institutionId, UUID id);

    /**
     * The plan, locked until the end of the transaction ({@code SELECT ... FOR UPDATE} on its row), so an activation, an
     * edit and the changes of its items take turns. Empty when it does not exist in the institution.
     */
    Optional<ChargePlan> lockById(InstitutionId institutionId, UUID id);

    void insert(InstitutionId institutionId, ChargePlan plan);

    /**
     * Writes the editable members of the plan and its new version.
     *
     * @param expectedVersion the version the row must still have
     * @throws com.confia.charges.domain.StaleVersionException when the row no longer has it
     * @throws com.confia.charges.domain.ResourceNotFoundException when the row does not exist in the institution
     */
    void update(InstitutionId institutionId, ChargePlan plan, long expectedVersion);

    Page<ChargePlan> list(InstitutionId institutionId, PageRequest request, ChargePlanFilter filter);
}
