package com.confia.ledger.application.api;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Output port, read-only, through which another module reads the derived statement of a student receivable sub-account
 * (docs/cortes/ledger-account-statement.md). Everything is derived from the entries at query time: no balance is stored.
 * Like {@link LedgerAccountDirectory}, it opens no transaction and applies no security context: it runs inside the
 * transaction of the caller, and row-level security plus the institution given are the two filters. The sign convention is
 * the one of the ledger: the receivable is an asset, so a debit increases what the student owes; a balance is positive
 * when the student owes and negative when there is a credit balance.
 */
public interface LedgerStatementApi {

    /** The largest page a caller may ask for. */
    int MAX_PAGE_SIZE = 100;

    /** The currencies in which the student has a sub-account, in alphabetical order; empty when it has none. */
    List<CurrencyCode> currenciesOf(InstitutionId institutionId, UUID studentId);

    /**
     * The statement of one sub-account for the effective dates {@code from} to {@code to}, both included: the opening
     * balance (everything before {@code from}), one page of lines in the canonical order (effective date, posting instant,
     * transaction, line) each with its running balance computed by the database, and the closing balance (everything up to
     * {@code to}). A student with no sub-account in the currency gets zeros and no lines.
     */
    Section statement(InstitutionId institutionId, UUID studentId, CurrencyCode currency, LocalDate from, LocalDate to,
            int page, int size);

    /**
     * One line of the statement: an entry of the sub-account. {@code debit} and {@code credit} are both present and one of
     * them is zero. {@code kind} is the name of the kind of the transaction ({@code CHARGE}, {@code DISCOUNT}, {@code PAYMENT},
     * {@code LATE_FEE}, {@code CREDIT_NOTE}, {@code REVERSAL}, {@code ADJUSTMENT} or {@code WRITE_OFF}); {@code reason} is only present when the transaction carries one (always for a reversal or an adjustment).
     */
    record Line(UUID transactionId, String kind, LocalDate effectiveOn, Instant postedAt, int lineNumber,
            String sourceModule, String reason, UUID reversesTransactionId, Money debit, Money credit, Money balance) {

        public Line {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(effectiveOn, "effectiveOn");
            Objects.requireNonNull(debit, "debit");
            Objects.requireNonNull(credit, "credit");
            Objects.requireNonNull(balance, "balance");
        }
    }

    /** The statement of one currency: balances, the count of lines of the period and one page of them. */
    record Section(CurrencyCode currency, Money openingBalance, Money closingBalance, long totalLines, int page, int size,
            List<Line> lines) {

        public Section {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(openingBalance, "openingBalance");
            Objects.requireNonNull(closingBalance, "closingBalance");
            lines = List.copyOf(lines);
        }
    }
}
