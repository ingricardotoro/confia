package com.confia.shared.audit;

/**
 * Write side of {@code shared_audit_log}, deferred by change 5B (audit-log-and-transaction-runner)
 * to this change (design.md, decision 8; specs/identity/spec.md, requirement "Puerto y adaptador
 * de escritura de la bitácora de auditoría"). {@link com.confia.shared.infrastructure.JooqAuditLogWriter}
 * is the single adapter, exactly as {@link AuditLogReader} has exactly one (ADR-0015 rule 4, R1:
 * jOOQ confined to {@code infrastructure}).
 */
public interface AuditLogWriter {

    /**
     * Inserts one row of {@code shared_audit_log} inside the transaction the caller already
     * opened. Does NOT compute {@code id}, {@code prev_hash} nor {@code row_hash}: the chaining
     * trigger ({@code shared_audit_log_chain()}, V3__chain_shared_audit_log.sql) assigns all
     * three, unconditionally overwriting whatever a caller supplied — which {@link AuditEntry}
     * makes structurally impossible to supply in the first place.
     */
    void append(AuditEntry entry);
}
