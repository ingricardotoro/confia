package com.confia.payments.domain;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InvalidMoneyAmountException;
import com.confia.kernel.Money;
import java.math.RoundingMode;

/**
 * The amount a cashier types: a decimal string in the currency of the institution (HNL), greater than zero (INV-19) and with at
 * most the decimals of its minor unit. Parsing goes through {@link Money}; this class only adds what a payment needs.
 */
public final class PaymentAmounts {

    /** The currency of every payment of this cut: the one of the institution (HNL). */
    public static final CurrencyCode CURRENCY = CurrencyCode.HNL;

    private PaymentAmounts() {
    }

    /** A positive amount of the payment currency with at most two decimals; any other value is a validation error. */
    public static Money parse(String amount) {
        if (amount == null) {
            throw new InvalidPaymentsValueException();
        }
        Money money;
        try {
            money = Money.of(amount, CURRENCY);
        } catch (InvalidMoneyAmountException e) {
            throw new InvalidPaymentsValueException();
        }
        return requireValid(money);
    }

    /** The same checks for an amount that is already a {@link Money}: positive, in HNL, in the minor unit. */
    public static Money requireValid(Money money) {
        if (money.currency() != CURRENCY || !money.isPositive()
                || !money.equals(money.roundToMinorUnit(RoundingMode.HALF_UP))) {
            throw new InvalidPaymentsValueException();
        }
        return money;
    }
}
