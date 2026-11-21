package com.confia.charges.domain;

import com.confia.kernel.Money;
import java.util.Objects;

/**
 * What has been paid on a charge and what is still outstanding, DERIVED every time from the total of the charge and the sum of
 * the allocations of the payments (docs/cortes/payments-register.md; rule 4 of CLAUDE.md): neither is a stored field. A voided
 * charge owes nothing and has no payments (a charge with payments cannot be voided). The outstanding amount never goes below
 * zero, whatever the allocations say.
 */
public record ChargeBalance(Money paid, Money outstanding) {

    public ChargeBalance {
        Objects.requireNonNull(paid, "paid");
        Objects.requireNonNull(outstanding, "outstanding");
    }

    /**
     * @param total the total of the charge
     * @param allocated the sum of the allocations of the payments to the charge, in the same currency
     * @param voided whether a void record exists for the charge
     */
    public static ChargeBalance of(Money total, Money allocated, boolean voided) {
        Money none = Money.zero(total.currency());
        if (voided) {
            return new ChargeBalance(none, none);
        }
        Money remaining = total.subtract(allocated);
        return new ChargeBalance(allocated, remaining.isNegative() ? none : remaining);
    }
}
