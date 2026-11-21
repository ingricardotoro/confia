package com.confia.payments.application;

import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.payments.domain.PaymentAllocation;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Output port of the allocations of the payments to the charges. It inserts and reads: nothing is updated or deleted, and the
 * database refuses an allocation that would exceed a charge or its payment (INV-17, INV-18).
 */
public interface PaymentAllocationRepository {

    void insert(InstitutionId institution, PaymentAllocation allocation);

    /** The allocations of the payment, in the order they were made. */
    List<PaymentAllocation> findByPayment(InstitutionId institution, UUID paymentId);

    /** The sum allocated to each of these charges; a charge with no allocation is absent. */
    Map<UUID, Money> allocatedByCharge(InstitutionId institution, Collection<UUID> chargeIds);

    boolean chargeHasAllocations(InstitutionId institution, UUID chargeId);
}
