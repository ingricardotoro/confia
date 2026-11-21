package com.confia.payments.domain;

import com.confia.kernel.Money;
import java.util.List;
import java.util.Objects;

/**
 * How much of a payment was applied to charges and how much stayed as a credit in the receivable of the student (INV-18),
 * DERIVED from its allocations every time. Only a confirmed payment covers anything: a declared transfer touches no balance and a
 * rejected one never did, so both show zero and zero. The sum of the allocations never exceeds the payment.
 */
public record PaymentCoverage(Money allocated, Money unallocated) {

    public PaymentCoverage {
        Objects.requireNonNull(allocated, "allocated");
        Objects.requireNonNull(unallocated, "unallocated");
    }

    public static PaymentCoverage of(Payment payment, List<PaymentAllocation> allocations) {
        Money none = Money.zero(payment.amount().currency());
        if (payment.status() != PaymentStatus.CONFIRMED) {
            return new PaymentCoverage(none, none);
        }
        Money allocated = none;
        for (PaymentAllocation allocation : allocations) {
            allocated = allocated.add(allocation.amount());
        }
        Money remainder = payment.amount().subtract(allocated);
        return new PaymentCoverage(allocated, remainder.isNegative() ? none : remainder);
    }
}
