package com.confia.cashbox.application;

import com.confia.cashbox.domain.CashMovement;
import com.confia.kernel.Money;
import java.time.Instant;
import java.util.UUID;

/** A movement as the use cases answer it: no domain type, so the web layer never sees one. The notes may name a person. */
public record MovementView(UUID id, UUID sessionId, String type, Money amount, String notes, UUID createdBy,
        Instant createdAt) {

    static MovementView of(CashMovement movement) {
        return new MovementView(movement.id(), movement.sessionId(), movement.type().code(), movement.amount(),
                movement.notes(), movement.createdBy(), movement.createdAt());
    }

    @Override
    public String toString() {
        return "MovementView[id=" + id + ", sessionId=" + sessionId + ", type=" + type + "]";
    }
}
