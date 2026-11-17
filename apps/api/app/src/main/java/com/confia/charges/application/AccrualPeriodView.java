package com.confia.charges.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A period of a plan that a run can accrue: its first day, its label, the day it falls due, its concepts and its last run. */
public record AccrualPeriodView(LocalDate periodStart, String label, LocalDate dueOn, List<UUID> paymentConceptIds,
        AccrualRunView lastRun) {

    public AccrualPeriodView {
        paymentConceptIds = List.copyOf(paymentConceptIds);
    }
}
