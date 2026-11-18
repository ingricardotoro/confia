package com.confia.charges.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One period of a plan item: the first and the last day (both included), the day it falls due and its label ({@code
 * 2027-02}, the year and month of its first day). All of them are dates, never instants: the period of a charge does not
 * depend on a clock or a time zone.
 */
public record AccrualPeriod(LocalDate start, LocalDate end, LocalDate dueOn, String label) {

    public AccrualPeriod {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(dueOn, "dueOn");
        Objects.requireNonNull(label, "label");
        if (start.getDayOfMonth() != 1 || end.isBefore(start) || dueOn.isBefore(start)) {
            throw new InvalidChargesValueException();
        }
    }
}
