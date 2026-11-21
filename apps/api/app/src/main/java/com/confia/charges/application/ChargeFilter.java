package com.confia.charges.application;

import com.confia.charges.domain.ChargeStatus;
import com.confia.charges.domain.InvalidChargesValueException;
import java.time.LocalDate;
import java.util.Set;

/**
 * What a list of the charges of a student may be narrowed by, all optional: the derived status ({@code ACTIVE} or {@code
 * VOIDED}), the first day of the period between two dates (both included) and {@code outstandingOnly}: only the charges with something
 * still owed, which is derived from the allocations of the payments (so it is applied by the use case, not by the query). The status travels as text so that the web layer
 * never names a type of the domain; a value outside the set is refused here.
 */
public record ChargeFilter(String status, LocalDate periodFrom, LocalDate periodTo, boolean outstandingOnly) {

    /** The fields a list of charges may be sorted by; a field outside the set is refused before it reaches a query. */
    public static final Set<String> SORT_FIELDS = Set.of("periodStart", "dueOn", "total");

    public ChargeFilter(String status, LocalDate periodFrom, LocalDate periodTo) {
        this(status, periodFrom, periodTo, false);
    }

    public ChargeFilter {
        if (status != null) {
            statusOf(status);
        }
        if (periodFrom != null && periodTo != null && periodFrom.isAfter(periodTo)) {
            throw new InvalidChargesValueException();
        }
    }

    public static ChargeFilter none() {
        return new ChargeFilter(null, null, null, false);
    }

    /** The status asked for, or {@code null} when the list is not narrowed by it. */
    public ChargeStatus statusOrNull() {
        return status == null ? null : statusOf(status);
    }

    private static ChargeStatus statusOf(String value) {
        try {
            return ChargeStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidChargesValueException();
        }
    }
}
