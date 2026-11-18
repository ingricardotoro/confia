package com.confia.charges.application;

import com.confia.charges.domain.ChargeStatus;
import com.confia.kernel.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A charge of a student as a list shows it. {@code voided} is {@code null} for an active charge. The amounts are exact; the
 * web layer rounds them for presentation.
 */
public record ChargeRow(UUID id, UUID paymentConceptId, String conceptCategory, String periodLabel,
        LocalDate periodStart, LocalDate periodEnd, LocalDate dueOn, String origin, Money base, Money tax, Money total,
        VoidInfo voided) {

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
