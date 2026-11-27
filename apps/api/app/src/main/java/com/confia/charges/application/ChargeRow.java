package com.confia.charges.application;

import com.confia.charges.domain.AppliedBenefit;
import com.confia.charges.domain.ChargeStatus;
import com.confia.kernel.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A charge of a student as a list shows it. {@code benefit} is the total that the benefits took off the {@code base} (zero when none
 * did), {@code netBase} is what is left and the tax was computed on, and the award and the scholarship code say which scholarship it
 * was (absent when none, or when only discounts reduced it). {@code benefits} lists every benefit that reduced the charge (the
 * scholarship and the discounts), in the order they were applied; a charge from before the lines existed shows its scholarship as the
 * only line. {@code voided} is {@code null} for an active charge. The amounts are exact; the web layer rounds them for presentation.
 */
public record ChargeRow(UUID id, UUID paymentConceptId, String conceptCategory, String periodLabel,
        LocalDate periodStart, LocalDate periodEnd, LocalDate dueOn, String origin, Money base, Money benefit, Money netBase,
        UUID scholarshipAwardId, String scholarshipCode, Money tax, Money total, VoidInfo voided,
        List<AppliedBenefit> benefits) {

    public ChargeRow {
        benefits = benefits == null ? List.of() : List.copyOf(benefits);
    }

    /** The same charge with the benefits that reduced it. */
    public ChargeRow withBenefits(List<AppliedBenefit> applied) {
        return new ChargeRow(id, paymentConceptId, conceptCategory, periodLabel, periodStart, periodEnd, dueOn, origin, base,
                benefit, netBase, scholarshipAwardId, scholarshipCode, tax, total, voided, applied);
    }

    /** What a void leaves on the charge it voided. */
    public record VoidInfo(UUID voidId, UUID reversalTransactionId, Instant voidedAt, String reason) {
    }

    public ChargeStatus status() {
        return voided == null ? ChargeStatus.ACTIVE : ChargeStatus.VOIDED;
    }

    /** The derived status as text, for the layer that answers the client. */
    public String statusName() {
        return status().name();
    }
}
