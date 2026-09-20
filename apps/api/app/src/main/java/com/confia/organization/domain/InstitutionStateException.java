package com.confia.organization.domain;

import com.confia.kernel.DomainException;

/**
 * {@link Institution}'s transition invariant causes (design.md, decision 6; ADR-0019): activating
 * an already active institution, or deactivating an already inactive one. Transitions are
 * deliberately not idempotent — a double state change is a caller defect, not a valid operation
 * (specs/organization/spec.md, requirement "Activación y desactivación de una institución").
 */
public final class InstitutionStateException extends DomainException {

    public static final String ALREADY_ACTIVE = "institution-already-active";
    public static final String ALREADY_INACTIVE = "institution-already-inactive";

    private InstitutionStateException(String code, String message) {
        super(code, message);
    }

    /** {@link Institution#activate()} was invoked on an institution that was already active. */
    static InstitutionStateException alreadyActive() {
        return new InstitutionStateException(ALREADY_ACTIVE, "institution is already active");
    }

    /** {@link Institution#deactivate()} was invoked on an institution that was already inactive. */
    static InstitutionStateException alreadyInactive() {
        return new InstitutionStateException(ALREADY_INACTIVE, "institution is already inactive");
    }
}
