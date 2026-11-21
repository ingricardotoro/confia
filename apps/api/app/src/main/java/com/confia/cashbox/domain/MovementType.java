package com.confia.cashbox.domain;

import com.confia.kernel.Money;
import java.util.Arrays;
import java.util.Optional;

/**
 * What a cash movement is. A deposit adds cash, a withdrawal takes it out, and an adjustment corrects the count with a signed
 * amount and a reason: nothing is edited, a mistake is answered with a new movement. {@code payment_in} is the cash that a
 * cash payment of {@code payments} leaves in the session of the cashier (docs/cortes/payments-register.md): it adds cash like a
 * deposit, so the formula of the expected amount does not change, but only a payment writes it, never a cashier through the
 * routes of the movements.
 */
public enum MovementType {
    DEPOSIT("deposit"), WITHDRAWAL("withdrawal"), ADJUSTMENT("adjustment"), PAYMENT_IN("payment_in");

    private final String code;

    MovementType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static Optional<MovementType> ofCode(String code) {
        return Arrays.stream(values()).filter(type -> type.code.equals(code)).findFirst();
    }

    /** Whether a cashier may record this type through the routes of the movements: the cash of a payment is never typed. */
    public boolean recordedByTheCashier() {
        return this != PAYMENT_IN;
    }

    /** The effect of a movement of this type and amount on the expected cash: withdrawals subtract, the others add. */
    public Money effect(Money amount) {
        return this == WITHDRAWAL ? amount.negate() : amount;
    }
}
