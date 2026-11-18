package com.confia.charges.domain;

import com.confia.kernel.InstitutionId;

/**
 * Raised by the nightly integrity check AFTER it recorded its result, so the occurrence of the recurring job stays visible as
 * failed until a later clean run. It is control flow toward the job runner, never an error of a request, and carries no stack.
 */
public final class IntegrityDiscrepanciesFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient InstitutionId institution;
    private final int discrepancies;

    public IntegrityDiscrepanciesFoundException(InstitutionId institution, int discrepancies) {
        super("the integrity check of the charges found " + discrepancies + " discrepancies", null, false, false);
        this.institution = institution;
        this.discrepancies = discrepancies;
    }

    public InstitutionId institution() {
        return institution;
    }

    public int discrepancies() {
        return discrepancies;
    }
}
