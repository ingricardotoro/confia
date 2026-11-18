package com.confia.ledger.application;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import com.confia.shared.security.TransactionRunner;

/**
 * The reads of the ledger. A balance is never stored: it is debits minus credits of the entries of the sub-account up to a
 * date (INV-07); positive is debt, negative is a credit balance. A page is capped by the server.
 */
public final class LedgerQueries {

    public static final int MAX_PAGE_SIZE = 100;

    private final TransactionRunner transactionRunner;
    private final LedgerRepository ledger;

    public LedgerQueries(TransactionRunner transactionRunner, LedgerRepository ledger) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    /** The balance on {@code asOf} (by effective date); with {@code knownAt}, as the ledger knew it at that instant. */
    public Money balanceOf(LedgerActor actor, UUID studentId, CurrencyCode currency, LocalDate asOf, Instant knownAt) {
        Objects.requireNonNull(asOf, "asOf");
        return transactionRunner.executeReadOnly(actor.securityContext(),
                () -> ledger.balance(new InstitutionId(actor.institutionId()), studentId, currency, asOf, knownAt));
    }

    /** The entries of a sub-account in the canonical order (effective date, posting instant, id). */
    public Page<EntryView> entries(LedgerActor actor, UUID studentId, CurrencyCode currency, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new com.confia.ledger.domain.InvalidLedgerEntryException(
                    "the page is zero or more and the size is between 1 and " + MAX_PAGE_SIZE);
        }
        return transactionRunner.executeReadOnly(actor.securityContext(),
                () -> ledger.listEntries(new InstitutionId(actor.institutionId()), studentId, currency, page, size));
    }
}
