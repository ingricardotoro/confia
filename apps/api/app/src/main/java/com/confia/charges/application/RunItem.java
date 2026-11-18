package com.confia.charges.application;

import com.confia.charges.domain.AccrualErrorCode;
import java.util.Objects;
import java.util.UUID;

/** One item of a run: the outcome for a student and a concept, with the charge created, the one found, or the error. */
public record RunItem(UUID studentId, UUID paymentConceptId, AccrualOutcome outcome, AccrualErrorCode errorCode,
        UUID chargeId, UUID existingChargeId) {

    public RunItem {
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(paymentConceptId, "paymentConceptId");
        Objects.requireNonNull(outcome, "outcome");
    }

    /** The code of the error as text, or {@code null} when there is none. */
    public String error() {
        return errorCode == null ? null : errorCode.name();
    }

    static RunItem created(UUID studentId, UUID conceptId, UUID chargeId) {
        return new RunItem(studentId, conceptId, AccrualOutcome.CREATED, null, chargeId, null);
    }

    static RunItem existing(UUID studentId, UUID conceptId, AccrualOutcome outcome, UUID existingChargeId) {
        return new RunItem(studentId, conceptId, outcome, null, null, existingChargeId);
    }

    static RunItem error(UUID studentId, UUID conceptId, AccrualErrorCode code) {
        return new RunItem(studentId, conceptId, AccrualOutcome.ERROR, code, null, null);
    }
}
