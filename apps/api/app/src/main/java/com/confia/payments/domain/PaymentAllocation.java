package com.confia.payments.domain;

import com.confia.kernel.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** The part of a confirmed payment that was applied to one charge. Born once, never edited or deleted (INV-17, INV-18). */
public record PaymentAllocation(UUID id, UUID paymentId, UUID chargeId, Money amount, Instant allocatedAt) {

    public PaymentAllocation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(chargeId, "chargeId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(allocatedAt, "allocatedAt");
        if (!amount.isPositive()) {
            throw new InvalidPaymentsValueException();
        }
    }
}
