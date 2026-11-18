package com.confia.charges.application;

import com.confia.charges.domain.ChargePlan;
import java.time.Instant;
import java.util.UUID;

/** A charge plan as the web layer may see it; the scope, the frequency and the status are the names of their members. */
public record ChargePlanView(UUID id, UUID academicYearId, String name, String targetScope, UUID targetRefId,
        String frequency, int dueDayOfMonth, String status, long version, Instant createdAt, Instant updatedAt) {

    static ChargePlanView of(ChargePlan plan) {
        return new ChargePlanView(plan.id(), plan.academicYearId(), plan.name(), plan.targetScope().name(),
                plan.targetRefId(), plan.frequency().name(), plan.dueDayOfMonth(), plan.status().name(),
                plan.version(), plan.createdAt(), plan.updatedAt());
    }
}
