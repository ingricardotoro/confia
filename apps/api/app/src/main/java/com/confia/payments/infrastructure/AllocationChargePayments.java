package com.confia.payments.infrastructure;

import com.confia.charges.application.api.ChargePayments;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.payments.application.PaymentAllocationRepository;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Tells {@code charges} what has been paid on a charge, from the allocations of the payments (docs/cortes/payments-register.md):
 * the sum allocated to each charge, so the list of the charges of a student shows what is paid and outstanding, and whether a
 * charge has any allocation at all, so a charge with payments is not voided. It reads only, inside the transaction of the
 * caller, and it is the one implementation of the port in the administrative process.
 */
public final class AllocationChargePayments implements ChargePayments {

    private final PaymentAllocationRepository allocations;

    public AllocationChargePayments(PaymentAllocationRepository allocations) {
        this.allocations = Objects.requireNonNull(allocations, "allocations");
    }

    @Override
    public Map<UUID, Money> allocatedByCharge(InstitutionId institutionId, Collection<UUID> chargeIds) {
        return allocations.allocatedByCharge(institutionId, chargeIds);
    }

    @Override
    public boolean hasAllocations(InstitutionId institutionId, UUID chargeId) {
        return allocations.chargeHasAllocations(institutionId, chargeId);
    }
}
