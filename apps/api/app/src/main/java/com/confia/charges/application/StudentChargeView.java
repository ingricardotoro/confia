package com.confia.charges.application;

import com.confia.kernel.Money;

/**
 * A charge of a student with the code and the name of its concept, which the charge itself does not hold, and what has been paid
 * and what is still outstanding on it, both derived from the allocations of the payments (never stored).
 */
public record StudentChargeView(ChargeRow charge, String conceptCode, String conceptName, Money paid, Money outstanding) {

    /** Derived, never stored: the charge is active and nothing is outstanding on it. A voided charge owes nothing and is not settled. */
    public boolean settled() {
        return charge.voided() == null && outstanding.isZero();
    }
}
