package com.confia.payments.domain;

import com.confia.kernel.Money;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A charge of the student that is still active, as the allocation sees it: what it totals and what earlier payments already
 * covered. What is outstanding is derived here every time and never stored (rule 4 of CLAUDE.md). {@code conceptCode} only breaks
 * a tie of due date and period, so a charge whose concept has no code sorts after the others.
 */
public record OpenCharge(UUID chargeId, UUID paymentConceptId, String conceptCode, LocalDate periodStart, LocalDate dueOn,
        Money total, Money allocated) {

    public OpenCharge {
        Objects.requireNonNull(chargeId, "chargeId");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(dueOn, "dueOn");
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(allocated, "allocated");
    }

    /** What is still owed on the charge: its total minus what earlier payments covered, never below zero. */
    public Money pending() {
        Money pending = total.subtract(allocated);
        return pending.isNegative() ? Money.zero(total.currency()) : pending;
    }
}
