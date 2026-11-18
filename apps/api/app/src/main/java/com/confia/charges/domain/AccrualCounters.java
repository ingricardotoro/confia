package com.confia.charges.domain;

/** What a run did: the students it evaluated and the charges it created, found already accrued or could not create. */
public record AccrualCounters(int studentsEvaluated, int chargesCreated, int chargesExisting, int chargesFailed) {

    public static final AccrualCounters ZERO = new AccrualCounters(0, 0, 0, 0);

    public AccrualCounters {
        if (studentsEvaluated < 0 || chargesCreated < 0 || chargesExisting < 0 || chargesFailed < 0) {
            throw new InvalidChargesValueException();
        }
    }
}
