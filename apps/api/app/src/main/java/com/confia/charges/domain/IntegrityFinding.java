package com.confia.charges.domain;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * One discrepancy: its kind, the charge and the ledger transaction it is about (either may be absent) and, when the
 * discrepancy is about an amount, what was expected and what the ledger holds, in the same currency.
 */
public record IntegrityFinding(IntegrityKind kind, UUID chargeId, UUID ledgerTransactionId, Money expected,
        Money actual) {

    public IntegrityFinding {
        Objects.requireNonNull(kind, "kind");
        if (chargeId == null && ledgerTransactionId == null && kind != IntegrityKind.RECEIVABLE_MISMATCH) {
            throw new IllegalArgumentException("a finding names a charge or a transaction");
        }
        if ((expected == null) != (actual == null)
                || (expected != null && expected.currency() != actual.currency())) {
            throw new IllegalArgumentException("expected and actual travel together, in one currency");
        }
        if (kind == IntegrityKind.RECEIVABLE_MISMATCH && expected == null) {
            throw new IllegalArgumentException("a receivable mismatch carries its amounts");
        }
    }

    /** The currency of the amounts, or {@code null} when the finding carries none. */
    public CurrencyCode currency() {
        return expected == null ? null : expected.currency();
    }

    /** What makes two findings the same fact in two readings: the kind and what it is about, not the amounts. */
    String identity() {
        return kind + "|" + chargeId + "|" + ledgerTransactionId + "|" + currency();
    }
}
