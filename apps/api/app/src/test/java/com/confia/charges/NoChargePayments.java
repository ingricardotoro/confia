package com.confia.charges;

import com.confia.charges.application.api.ChargePayments;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Test-only {@link ChargePayments}: a database in which no payment was ever made. Production never has a fallback like it. */
public final class NoChargePayments implements ChargePayments {

    public static final ChargePayments INSTANCE = new NoChargePayments();

    private NoChargePayments() {
    }

    @Override
    public Map<UUID, Money> allocatedByCharge(InstitutionId institutionId, Collection<UUID> chargeIds) {
        return Map.of();
    }

    @Override
    public boolean hasAllocations(InstitutionId institutionId, UUID chargeId) {
        return false;
    }
}
