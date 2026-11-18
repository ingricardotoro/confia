package com.confia.charges.application;

import java.util.Objects;
import java.util.UUID;

/**
 * The payload of the accrual job: the identifier of the run and nothing else (ADR-0016 rule 3). The institution travels in
 * the metadata of the job, and the run in the database is the source of truth for everything else.
 */
public record AccrualRunJob(UUID runId) {

    public AccrualRunJob {
        Objects.requireNonNull(runId, "runId");
    }
}
