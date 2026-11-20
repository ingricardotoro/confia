package com.confia.cashbox.application;

import com.confia.cashbox.domain.CashClose;
import com.confia.kernel.Money;
import java.time.Instant;
import java.util.UUID;

/** The close of a session as the use cases answer it; the difference is declared minus expected, computed by the server. */
public record CloseView(UUID id, Money declaredAmount, Money expectedAmount, Money differenceAmount,
        String differenceReason, UUID closedBy, Instant closedAt) {

    static CloseView of(CashClose close) {
        return new CloseView(close.id(), close.declaredAmount(), close.expectedAmount(), close.differenceAmount(),
                close.differenceReason(), close.closedBy(), close.closedAt());
    }

    @Override
    public String toString() {
        return "CloseView[id=" + id + "]";
    }
}
