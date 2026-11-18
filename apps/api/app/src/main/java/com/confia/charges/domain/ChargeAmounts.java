package com.confia.charges.domain;

import com.confia.kernel.Money;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * The four amounts of a charge, in one currency and in the minor unit of it (INV-08, INV-09, INV-10): the base, the
 * discount, the tax and the total, where {@code total = base - discount + tax} EXACTLY (no rounding anywhere in a sum), the
 * discount never exceeds the base and nothing is negative. The database repeats every rule in a {@code CHECK}.
 */
public record ChargeAmounts(Money base, Money discount, Money tax, Money total) {

    public ChargeAmounts {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(discount, "discount");
        Objects.requireNonNull(tax, "tax");
        Objects.requireNonNull(total, "total");
        if (!base.isPositive() || discount.isNegative() || tax.isNegative() || discount.isGreaterThan(base)
                || !total.equals(base.subtract(discount).add(tax))
                || !inMinorUnit(base) || !inMinorUnit(discount) || !inMinorUnit(tax) || !inMinorUnit(total)) {
            throw new InvalidChargesValueException();
        }
    }

    /** The total is derived: base minus discount plus tax. */
    public static ChargeAmounts of(Money base, Money discount, Money tax) {
        return new ChargeAmounts(base, discount, tax, base.subtract(discount).add(tax));
    }

    private static boolean inMinorUnit(Money amount) {
        return amount.equals(amount.roundToMinorUnit(RoundingMode.HALF_UP));
    }
}
