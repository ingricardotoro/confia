package com.confia.charges.application;

import com.confia.charges.domain.AccrualRun;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** An accrual run as the web layer may see it: its state, its counters and when it happened. The request key stays inside. */
public record AccrualRunView(UUID id, UUID planId, LocalDate periodStart, String status, boolean previewChecked,
        Instant queuedAt, Instant startedAt, Instant finishedAt, int studentsEvaluated, int chargesCreated,
        int chargesExisting, int chargesFailed, String failureCode) {

    static AccrualRunView of(AccrualRun run) {
        return new AccrualRunView(run.id(), run.planId(), run.periodStart(), run.status().name(),
                run.previewDigest() != null, run.queuedAt(), run.startedAt(), run.finishedAt(),
                run.counters().studentsEvaluated(), run.counters().chargesCreated(), run.counters().chargesExisting(),
                run.counters().chargesFailed(), run.failureCode());
    }
}
