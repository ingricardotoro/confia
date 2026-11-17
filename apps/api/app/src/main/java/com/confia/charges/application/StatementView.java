package com.confia.charges.application;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The derived statement of a student (docs/cortes/ledger-account-statement.md): who, the effective-date range and one
 * section per currency the student has. Nothing here is stored; every amount comes from the entries of the ledger.
 */
public record StatementView(UUID studentId, String studentCode, String firstName, String lastName, LocalDate from,
        LocalDate to, List<Section> sections) {

    public StatementView {
        sections = List.copyOf(sections);
    }

    /** Personal data of a minor never reaches a log through this method. */
    @Override
    public String toString() {
        return "StatementView[studentId=" + studentId + "]";
    }

    /** One currency: balances, the number of lines of the period and one page of them. */
    public record Section(CurrencyCode currency, Money openingBalance, Money closingBalance, long totalLines, int page,
            int size, List<Line> lines) {

        public Section {
            lines = List.copyOf(lines);
        }
    }

    /** One entry of the sub-account, with the charge that produced it when the transaction is a charge. */
    public record Line(UUID transactionId, String kind, LocalDate effectiveOn, Instant postedAt, int lineNumber,
            String reason, UUID reversesTransactionId, Money debit, Money credit, Money balance, ChargeInfo charge) {
    }

    /** The charge behind a line: its concept, its period and its due date. */
    public record ChargeInfo(UUID chargeId, UUID paymentConceptId, String conceptCode, String conceptName,
            String conceptCategory, String periodLabel, LocalDate periodStart, LocalDate periodEnd, LocalDate dueOn,
            String origin) {
    }
}
