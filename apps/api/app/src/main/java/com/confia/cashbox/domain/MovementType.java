package com.confia.cashbox.domain;

import com.confia.kernel.Money;
import java.util.Arrays;
import java.util.Optional;

/**
 * What a cash movement is. A deposit adds cash, a withdrawal takes it out, and an adjustment corrects the count with a signed
 * amount and a reason: nothing is edited, a mistake is answered with a new movement. {@code payment_in} arrives with the
 * payments cut and does not change the formula of the expected amount.
 */
public enum MovementType {
    DEPOSIT("deposit"), WITHDRAWAL("withdrawal"), ADJUSTMENT("adjustment");

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

    /** The effect of a movement of this type and amount on the expected cash: deposits add, withdrawals subtract. */
    public Money effect(Money amount) {
        return this == WITHDRAWAL ? amount.negate() : amount;
    }
}
