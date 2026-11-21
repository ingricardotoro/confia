package com.confia.charges.application.api;

import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Port that {@code payments} IMPLEMENTS to tell {@code charges} what has been paid on a charge (docs/cortes/payments-register.md):
 * the sum allocated to each charge and whether a charge has any allocation at all. It is an inversion of the dependency, like
 * {@code LedgerAccountUsage}: {@code charges} knows no module, {@code payments} knows this interface, and the module graph has no
 * cycle ({@code payments} reads the charges, never the other way round). It runs inside the transaction of the caller. There is no "no payments" implementation in production: a process that loads the charges use cases without {@code payments} fails at startup.
 */
public interface ChargePayments {

    /** The sum allocated to each of these charges; a charge with no allocation is absent. */
    Map<UUID, Money> allocatedByCharge(InstitutionId institutionId, Collection<UUID> chargeIds);

    /** Whether any payment has been allocated to the charge: such a charge cannot be voided. */
    boolean hasAllocations(InstitutionId institutionId, UUID chargeId);
}
