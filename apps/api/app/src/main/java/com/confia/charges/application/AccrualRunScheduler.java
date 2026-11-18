package com.confia.charges.application;

import com.confia.kernel.InstitutionId;
import java.util.UUID;

/**
 * Schedules the execution of an accrual run as a one-time background job (ADR-0016). It carries identifiers only and is
 * called inside the transaction that creates the run, so the job exists if and only if that transaction commits. Only the
 * worker process executes it.
 */
public interface AccrualRunScheduler {

    void schedule(InstitutionId institutionId, UUID runId, UUID requestId, UUID requestedBy);
}
