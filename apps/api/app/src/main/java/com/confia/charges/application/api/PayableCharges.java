package com.confia.charges.application.api;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port, read-only, through which {@code payments} asks {@code charges} what a payment can be applied to
 * (docs/cortes/payments-register.md): the receivable account that the charges are posted to and the charges of a student that are
 * still active (a voided charge is never offered). It runs inside the transaction of the caller, opens none and takes no lock:
 * the caller locks the student sub-account of the ledger first, the lock every writer of the charges of the student takes, and
 * reads under it. What is outstanding is not decided here: the caller subtracts what it has already allocated, so the charge
 * keeps no state of its own.
 */
public interface PayableCharges {

    /** The receivable account of the posting map of the institution, or empty when the map has none. */
    Optional<UUID> receivableAccount(InstitutionId institutionId);

    /** The active (not voided) charges of the student whose total is in this currency, in no particular order. */
    List<ActiveCharge> activeCharges(InstitutionId institutionId, UUID studentId, CurrencyCode currency);

    /**
     * What a payment shows of the charges it was applied to (concept, period, due date), by charge, in one query, voided charges
     * included; an identifier that is not a charge of the institution is absent.
     */
    Map<UUID, ChargeLabel> chargeLabels(InstitutionId institutionId, Collection<UUID> chargeIds);

    /** What identifies a charge to a reader: its concept and its period. */
    record ChargeLabel(UUID chargeId, UUID paymentConceptId, String conceptCode, String conceptName, String periodLabel,
            LocalDate dueOn) {
    }

    /** An active charge as a payment sees it; {@code conceptCode} is null when the concept has no readable code. */
    record ActiveCharge(UUID chargeId, UUID paymentConceptId, String conceptCode, LocalDate periodStart,
            LocalDate dueOn, Money total) {

        public ActiveCharge {
            Objects.requireNonNull(chargeId, "chargeId");
            Objects.requireNonNull(periodStart, "periodStart");
            Objects.requireNonNull(dueOn, "dueOn");
            Objects.requireNonNull(total, "total");
        }
    }
}
