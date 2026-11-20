package com.confia.cashbox.domain;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InvalidMoneyAmountException;
import com.confia.kernel.Money;
import java.math.RoundingMode;

/**
 * The amounts the staff member types: decimal strings in the currency of the institution, with at most the decimals of its minor
 * unit. Parsing goes through {@link Money}; this class only adds what a cash count needs (no sub-cent digits, the currency).
 */
public final class CashAmounts {

    /** The currency of every cash session of this cut: the one of the institution (HNL). */
    public static final CurrencyCode CURRENCY = CurrencyCode.HNL;

    private CashAmounts() {
    }

    /** An amount of the cash currency with at most two decimals. A malformed or too precise value is a validation error. */
    public static Money parse(String amount) {
        if (amount == null) {
            throw new InvalidCashboxValueException();
        }
        Money money;
        try {
            money = Money.of(amount, CURRENCY);
        } catch (InvalidMoneyAmountException e) {
            throw new InvalidCashboxValueException();
        }
        if (!money.equals(money.roundToMinorUnit(RoundingMode.HALF_UP))) {
            throw new InvalidCashboxValueException();
        }
        return money;
    }

    /** The same checks for a value that may be zero but not negative. */
    public static Money parseNotNegative(String amount) {
        Money money = parse(amount);
        if (money.isNegative()) {
            throw new InvalidCashboxValueException();
        }
        return money;
    }
}
