package com.confia.payments.domain;

import com.confia.kernel.Money;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * How a confirmed payment is spread over the open charges of the student (INV-17, INV-18; docs/cortes/payments-register.md). The
 * charges are served by due date, then by the first day of the period, then by the code of the concept and, last, by the
 * identifier of the charge, so the order is total and the same input always gives the same plan. Each charge receives at most
 * what is pending on it (a partial payment leaves a pending), the sum of the lines never exceeds the payment, and what is left
 * after every pending is covered is the {@link #unallocated() remainder}: it stays credited in the receivable of the student
 * (a credit balance) and is shown as such, it is never lost.
 *
 * <p>All the arithmetic is that of {@link Money}: there is no rounding because nothing here divides.
 */
public record AllocationPlan(List<Line> lines, Money unallocated) {

    /** The part of the payment that goes to one charge. */
    public record Line(UUID chargeId, Money amount) {

        public Line {
            Objects.requireNonNull(chargeId, "chargeId");
            Objects.requireNonNull(amount, "amount");
        }
    }

    private static final Comparator<OpenCharge> ORDER = Comparator.comparing(OpenCharge::dueOn)
            .thenComparing(OpenCharge::periodStart)
            .thenComparing(OpenCharge::conceptCode, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(OpenCharge::chargeId);

    public AllocationPlan {
        lines = List.copyOf(lines);
        Objects.requireNonNull(unallocated, "unallocated");
    }

    /**
     * The plan for a payment of {@code amount} over {@code open}.
     *
     * @throws InvalidPaymentsValueException when the amount is not positive
     * @throws com.confia.kernel.CurrencyMismatchException when a charge is in another currency than the payment
     */
    public static AllocationPlan of(Money amount, List<OpenCharge> open) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(open, "open");
        if (!amount.isPositive()) {
            throw new InvalidPaymentsValueException();
        }
        List<OpenCharge> served = open.stream().sorted(ORDER).toList();
        List<Line> lines = new ArrayList<>();
        Money remaining = amount;
        for (OpenCharge charge : served) {
            if (remaining.isZero()) {
                break;
            }
            Money pending = charge.pending();
            if (!pending.isPositive()) {
                continue;
            }
            Money take = pending.compareTo(remaining) < 0 ? pending : remaining;
            lines.add(new Line(charge.chargeId(), take));
            remaining = remaining.subtract(take);
        }
        return new AllocationPlan(lines, remaining);
    }

    /** The sum of the lines: what the payment covers of the charges. */
    public Money allocated(Money payment) {
        return payment.subtract(unallocated);
    }
}
