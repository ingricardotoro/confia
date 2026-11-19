package com.confia.cashbox.domain;

import com.confia.kernel.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One movement of cash in a session: born once, never edited or deleted (a correction is another movement). The key and its
 * hash make the request idempotent. The notes may name a person, so {@code toString} does not show them.
 *
 * @param amount positive for a deposit and a withdrawal, signed and not zero for an adjustment
 */
public record CashMovement(UUID id, UUID sessionId, MovementType type, Money amount, String notes, String requestKey,
        String requestHash, UUID createdBy, Instant createdAt) {

    public static final int NOTES_MAX = 500;
    public static final int ADJUSTMENT_NOTES_MIN = 20;

    public CashMovement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(requestKey, "requestKey");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** The signed effect of this movement on the expected cash. */
    public Money effect() {
        return type.effect(amount);
    }

    public boolean answers(String hash) {
        return requestHash.equals(hash);
    }

    @Override
    public String toString() {
        return "CashMovement[id=" + id + ", sessionId=" + sessionId + ", type=" + type + "]";
    }
}
