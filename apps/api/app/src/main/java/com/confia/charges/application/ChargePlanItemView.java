package com.confia.charges.application;

import com.confia.charges.domain.ChargePlanItem;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** An item of a charge plan as the web layer may see it. */
public record ChargePlanItemView(UUID id, UUID planId, UUID paymentConceptId, int periodsCount,
        LocalDate firstPeriodOn, Instant createdAt) {

    static ChargePlanItemView of(ChargePlanItem item) {
        return new ChargePlanItemView(item.id(), item.planId(), item.paymentConceptId(), item.periodsCount(),
                item.firstPeriodOn(), item.createdAt());
    }
}
