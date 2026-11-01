package com.confia.shared.audit;

import com.confia.kernel.InstitutionId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port over {@code shared_audit_log}, read-only (design.md decision 11). The single adapter, {@link
 * com.confia.shared.infrastructure.JooqAuditLogReader}, is the only place jOOQ touches this chain
 * (ADR-0015 rule 4, R1) — this interface and every type it returns stay JDK-only, so a caller such
 * as {@link DefaultAuditChainVerifier} never depends on {@code org.jooq}.
 */
public interface AuditLogReader {

    /**
     * A bounded page of {@code institutionId}'s rows, strictly ordered by {@code id} ascending
     * (design.md decision 11, "recorrido"): the chain of an institution can reach millions of rows,
     * so the verifier never materializes it whole. {@code afterId} is exclusive — {@code 0} to
     * start from the genesis row — and the caller drives pagination by passing the last row's
     * {@code id} back in on the next call.
     */
    List<AuditRowSnapshot> pageOf(InstitutionId institutionId, long afterId, int pageSize);

    /**
     * The first row of {@code institutionId}, in chain order, whose {@code occurred_at} is at or after {@code
     * occurredAt}, or empty when there is none (background-jobs-with-db-scheduler design.md decision 19). The
     * window of the daily verification starts there.
     */
    Optional<AuditRowSnapshot> firstAtOrAfter(InstitutionId institutionId, Instant occurredAt);
}
