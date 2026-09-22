package com.confia.shared.audit;

import com.confia.kernel.InstitutionId;
import java.util.List;

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
}
