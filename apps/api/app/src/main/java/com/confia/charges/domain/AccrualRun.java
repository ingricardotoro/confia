package com.confia.charges.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * An accrual run: the request to accrue the charges of a plan for one period (docs/cortes/charges-accrual.md). It is born
 * {@link RunStatus#QUEUED}, is taken by the worker ({@link #start}) and ends completed, completed with errors, rejected (a
 * precondition did not hold and nothing was written) or failed (the retries are spent). A finished run never changes. The
 * key of the request is permanent and the hash says what the key asked for, so the same key and body find this run again
 * and the same key with another body is refused.
 */
public record AccrualRun(UUID id, UUID planId, LocalDate periodStart, RunStatus status, String requestKey,
        String requestHash, String previewDigest, UUID requestedBy, Instant queuedAt, Instant startedAt,
        Instant finishedAt, AccrualCounters counters, String failureCode) {

    public AccrualRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(requestKey, "requestKey");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(queuedAt, "queuedAt");
        Objects.requireNonNull(counters, "counters");
        if (periodStart.getDayOfMonth() != 1) {
            throw new InvalidChargesValueException();
        }
    }

    /** A new run, queued, with no counters. */
    public static AccrualRun queue(UUID id, UUID planId, LocalDate periodStart, String requestKey, String requestHash,
            String previewDigest, UUID requestedBy, Instant now) {
        return new AccrualRun(id, planId, periodStart, RunStatus.QUEUED, requestKey, requestHash, previewDigest,
                requestedBy, now, null, null, AccrualCounters.ZERO, null);
    }

    /** The run taken by the worker. A run that is already running stays so: the resumption after a crash. */
    public AccrualRun start(Instant now) {
        if (status == RunStatus.RUNNING) {
            return this;
        }
        requireMove(RunStatus.RUNNING);
        return copy(RunStatus.RUNNING, now, null, counters, null);
    }

    public AccrualRun complete(AccrualCounters finalCounters, Instant now) {
        RunStatus next = finalCounters.chargesFailed() > 0 ? RunStatus.COMPLETED_WITH_ERRORS : RunStatus.COMPLETED;
        requireMove(next);
        return copy(next, startedAt, now, finalCounters, null);
    }

    public AccrualRun reject(AccrualRejection reason, Instant now) {
        requireMove(RunStatus.REJECTED);
        return copy(RunStatus.REJECTED, startedAt, now, counters, reason.code());
    }

    public AccrualRun fail(AccrualRejection reason, Instant now) {
        requireMove(RunStatus.FAILED);
        return copy(RunStatus.FAILED, startedAt, now, counters, reason.code());
    }

    private void requireMove(RunStatus next) {
        if (!status.canMoveTo(next)) {
            throw new IllegalStateException("an accrual run does not move from " + status + " to " + next);
        }
    }

    private AccrualRun copy(RunStatus next, Instant started, Instant finished, AccrualCounters newCounters,
            String failure) {
        return new AccrualRun(id, planId, periodStart, next, requestKey, requestHash, previewDigest, requestedBy, queuedAt,
                started, finished, newCounters, failure);
    }
}
