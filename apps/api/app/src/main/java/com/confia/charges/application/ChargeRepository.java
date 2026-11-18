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

    /**
     * The charges whose ledger transaction is one of these, by transaction, in one query; a transaction that is not the
     * posting of a charge (a payment, an adjustment, a reversal) is absent. A read for the statement of a student.
     */
    java.util.Map<UUID, StatementCharge> findByLedgerTransactions(InstitutionId institution,
            java.util.Collection<UUID> ledgerTransactionIds);

    /** The charge as the void needs it: who owes it, its ledger transaction and its total; absent for another institution. */
    java.util.Optional<ChargeForVoid> findForVoid(InstitutionId institution, UUID chargeId);

    /**
     * The charges of the student with their derived state (a void record means voided), filtered, one page, sorted by a
     * field of {@link ChargeFilter#SORT_FIELDS}. The sort is applied by the database and always ends on the identifier so pages are stable.
     */
    Page<ChargeRow> listByStudent(InstitutionId institution, UUID studentId, ChargeFilter filter, PageRequest page);

    /** Every charge of the institution as the integrity check compares it: its transaction, its total and its void. */
    List<com.confia.charges.domain.IntegrityCheck.ChargeFact> integrityFacts(InstitutionId institution);

    /** What the void of a charge needs to know about it. */
    record ChargeForVoid(UUID id, UUID studentId, UUID ledgerTransactionId, Money total, LocalDate periodStart) {
    }

    /** What a statement line shows of the charge that produced it. */
    record StatementCharge(UUID chargeId, UUID paymentConceptId, String conceptCategory, String periodLabel,
            LocalDate periodStart, LocalDate periodEnd, LocalDate dueOn, String origin) {
    }

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
