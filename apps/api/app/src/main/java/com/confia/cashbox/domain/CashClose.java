package com.confia.cashbox.domain;

import com.confia.kernel.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The close of a session: what the cashier declared against what the system expected (INV-23). The difference is {@code
 * declared - expected}; the database computes the same value in a generated column. A difference needs a reason and an
 * approver other than the cashier.
 */
public record CashClose(UUID id, UUID sessionId, Money declaredAmount, Money expectedAmount, String differenceReason,
        UUID closedBy, Instant closedAt, String requestKey, String requestHash) {

    public CashClose {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(declaredAmount, "declaredAmount");
        Objects.requireNonNull(expectedAmount, "expectedAmount");
        Objects.requireNonNull(closedBy, "closedBy");
        Objects.requireNonNull(closedAt, "closedAt");
        Objects.requireNonNull(requestKey, "requestKey");
        Objects.requireNonNull(requestHash, "requestHash");
        if ((differenceReason == null) != declaredAmount.equals(expectedAmount)) {
            throw new IllegalArgumentException("a reason goes with a difference and only with it");
        }
    }

    /** INV-23: declared minus expected. */
    public Money differenceAmount() {
        return declaredAmount.subtract(expectedAmount);
    }

    public boolean hasDifference() {
        return !differenceAmount().isZero();
    }

    /** {@code pending_approval} with a difference, {@code closed} without one. */
    public CashSessionStatus resultingStatus() {
        return hasDifference() ? CashSessionStatus.PENDING_APPROVAL : CashSessionStatus.CLOSED;
    }

    public boolean answers(String hash) {
        return requestHash.equals(hash);
    }

    @Override
    public String toString() {
        return "CashClose[id=" + id + ", sessionId=" + sessionId + "]";
    }
}
