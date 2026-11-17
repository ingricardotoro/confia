package com.confia.ledger.application;

import com.confia.kernel.CurrencyCode;
import java.util.Comparator;
import java.util.UUID;

/** The key of a student sub-account. Natural order is the fixed lock order: student, then currency. */
public record StudentCurrency(UUID studentId, CurrencyCode currency) implements Comparable<StudentCurrency> {

    private static final Comparator<StudentCurrency> ORDER =
            Comparator.comparing(StudentCurrency::studentId).thenComparing(StudentCurrency::currency);

    @Override
    public int compareTo(StudentCurrency other) {
        return ORDER.compare(this, other);
    }
}
