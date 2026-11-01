package com.confia.shared.audit;

import com.confia.kernel.InstitutionId;
import java.util.Objects;

/**
 * Thrown by a cadence of {@link AuditChainVerificationJobs} after it recorded a divergence, so the
 * occurrence ends as failed and stays visible in the queue until a later success (background-jobs-with-
 * db-scheduler design.md decision 19). The divergence is already in the audit trail and in the {@code ERROR}
 * event, so the message names nothing: the failure of a job is shown to whoever reads the queue.
 */
public final class AuditChainBrokenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient InstitutionId institutionId;
    private final long firstDivergentId;

    public AuditChainBrokenException(InstitutionId institutionId, long firstDivergentId) {
        super("the audit chain failed its verification");
        this.institutionId = Objects.requireNonNull(institutionId, "institutionId");
        this.firstDivergentId = firstDivergentId;
    }

    public InstitutionId institutionId() {
        return institutionId;
    }

    public long firstDivergentId() {
        return firstDivergentId;
    }
}
