package com.confia.charges.application;

import com.confia.charges.domain.PlanStatus;
import com.confia.charges.domain.PlanTargetScope;
import java.util.UUID;

/**
 * The optional filters of the list of charge plans; a {@code null} member does not filter.
 *
 * @param academicYearId only the plans of this academic year
 * @param targetScope only the plans of this target scope
 * @param status only the plans in this status
 */
public record ChargePlanFilter(UUID academicYearId, PlanTargetScope targetScope, PlanStatus status) {
}
