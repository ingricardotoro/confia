package com.confia.charges.domain;

/**
 * The state machine of an accrual run (docs/cortes/charges-accrual.md; INV-48). {@code QUEUED -> RUNNING} when the worker
 * takes it; {@code RUNNING -> COMPLETED | COMPLETED_WITH_ERRORS | REJECTED | FAILED}; {@code QUEUED -> REJECTED | FAILED}.
 * Nothing leaves a terminal state, {@code RUNNING} never goes back to {@code QUEUED} and a queued run never completes without
 * having run. The database repeats this in a trigger.
 */
public enum RunStatus {
    QUEUED, RUNNING, COMPLETED, COMPLETED_WITH_ERRORS, REJECTED, FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == COMPLETED_WITH_ERRORS || this == REJECTED || this == FAILED;
    }

    /** Whether a run in this status may move to {@code next} (staying in the same non-terminal status is not a move). */
    public boolean canMoveTo(RunStatus next) {
        return switch (this) {
            case QUEUED -> next == RUNNING || next == REJECTED || next == FAILED;
            case RUNNING -> next == COMPLETED || next == COMPLETED_WITH_ERRORS || next == REJECTED || next == FAILED;
            case COMPLETED, COMPLETED_WITH_ERRORS, REJECTED, FAILED -> false;
        };
    }
}
