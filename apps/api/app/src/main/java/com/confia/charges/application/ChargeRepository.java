package com.confia.charges.application;

import com.confia.charges.domain.ComputedCharge;
import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Output port of the charges. It inserts and reads: no operation updates or deletes a charge (INV-47), and the database
 * refuses them to every role. The charge is inserted AFTER its ledger transaction, in the same database transaction.
 */
public interface ChargeRepository {

    /** The automatic charges of the student and the concept whose period overlaps {@code [periodStart, periodEnd]}. */
    List<ExistingCharge> findOverlapping(InstitutionId institution, UUID studentId, UUID paymentConceptId,
            LocalDate periodStart, LocalDate periodEnd);

    /** Inserts the automatic charge the engine computed, with the ledger transaction that posted it. */
    void insertAutomatic(InstitutionId institution, UUID chargeId, ComputedCharge charge, UUID runId,
            UUID ledgerTransactionId, UUID createdBy);

    /** What the run created, by currency: the count and the exact sums of the base, the tax and the total. */
    List<CurrencyTotals> totalsOfRun(InstitutionId institution, UUID runId);

    /** An automatic charge that is already there. */
    record ExistingCharge(UUID id, UUID planId, LocalDate periodStart, LocalDate periodEnd) {

        public boolean samePeriodAs(LocalDate start, LocalDate end) {
            return periodStart.equals(start) && periodEnd.equals(end);
        }
    }

    /** The charges a run created in one currency, with exact sums. */
    record CurrencyTotals(CurrencyCode currency, int charges, Money base, Money tax, Money total) {
    }
}
