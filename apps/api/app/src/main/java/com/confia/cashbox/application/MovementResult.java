package com.confia.cashbox.application;

import com.confia.cashbox.domain.CashMovement;
import com.confia.kernel.Money;

/** A recorded movement with the expected cash of its session after it. */
public record MovementResult(MovementView movement, Money expectedAfter) {

    static MovementResult of(CashMovement movement, Money expectedAfter) {
        return new MovementResult(MovementView.of(movement), expectedAfter);
    }
}
