package com.confia.shared.infrastructure;

import static confia.generated.jooq.tables.SharedAuditLog.SHARED_AUDIT_LOG;

import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;

/**
 * The single jOOQ adapter of {@link AuditLogWriter}, and the first production writer of {@code
 * shared_audit_log} (design.md, decision 8; specs/identity/spec.md, "El primer escritor de
 * producción de {@code shared_audit_log}"). {@code final}, with an explicit constructor over
 * {@link DSLContext} and no Spring annotation, the same pattern {@link JooqAuditLogReader} already
 * established: no bootstrap process registers this as a bean yet.
 *
 * <p><b>Built with {@code .set()} explicit per column, never a full record</b> (design.md §14,
 * point 5): a full jOOQ record insert would carry {@code id}, {@code prev_hash} and {@code
 * row_hash} as explicit {@code NULL}s, tripping the chaining trigger's {@code NOT NULL} columns
 * before the trigger ever runs. Every column this adapter sets comes from {@link AuditEntry},
 * which has no field for any of those three, nor for {@code occurred_at} — left to the column's
 * own {@code DEFAULT clock_timestamp()}.
 */
public final class JooqAuditLogWriter implements AuditLogWriter {

    private final DSLContext dsl;

    public JooqAuditLogWriter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void append(AuditEntry entry) {
        dsl.insertInto(SHARED_AUDIT_LOG)
                .set(SHARED_AUDIT_LOG.INSTITUTION_ID, entry.institutionId())
                .set(SHARED_AUDIT_LOG.ACTOR_ID, entry.actorId())
                .set(SHARED_AUDIT_LOG.ACTOR_KIND, entry.actorKind())
                .set(SHARED_AUDIT_LOG.ACTOR_LABEL, entry.actorLabel())
                .set(SHARED_AUDIT_LOG.SOURCE_IP, sourceIpField(entry.sourceIp()))
                .set(SHARED_AUDIT_LOG.USER_AGENT, entry.userAgent())
                .set(SHARED_AUDIT_LOG.REQUEST_ID, entry.requestId())
                .set(SHARED_AUDIT_LOG.TRACE_ID, entry.traceId())
                .set(SHARED_AUDIT_LOG.ACTION, entry.action())
                .set(SHARED_AUDIT_LOG.ENTITY_TYPE, entry.entityType())
                .set(SHARED_AUDIT_LOG.ENTITY_ID, entry.entityId())
                .set(SHARED_AUDIT_LOG.OUTCOME, entry.outcome())
                .set(SHARED_AUDIT_LOG.BEFORE_VALUE, jsonbOf(entry.beforeValue()))
                .set(SHARED_AUDIT_LOG.AFTER_VALUE, jsonbOf(entry.afterValue()))
                .set(SHARED_AUDIT_LOG.REASON, entry.reason())
                .set(SHARED_AUDIT_LOG.APPROVER_ID, entry.approverId())
                .execute();
    }

    /** {@code null} exactly when {@link AuditEntry}'s own field is {@code null}. */
    private static JSONB jsonbOf(String json) {
        return json == null ? null : JSONB.valueOf(json);
    }

    /**
     * {@code source_ip} is {@code INET} in the database, but jOOQ's open-source code generator has
     * no native binding for that PostgreSQL type and generates {@link
     * confia.generated.jooq.tables.SharedAuditLog#SOURCE_IP} as a plain {@code VARCHAR}-typed
     * field (verified by reading the generated source: {@code SQLDataType.VARCHAR}, no {@code
     * INET} counterpart exists in this jOOQ edition). Binding a Java {@code String} straight into
     * that field sends PostgreSQL an explicitly {@code character varying}-typed parameter, which it
     * refuses to assign to an {@code inet} column without a cast. The explicit {@code ::inet} in
     * this template — not a plain bind — is what tells PostgreSQL the placeholder's type, the
     * standard way to bind a value into a type jOOQ itself does not model; this change never
     * supplies a non-null value here (design.md, decision 8), so the cast target for {@code null}
     * is the only path exercised today.
     */
    private static Field<String> sourceIpField(String sourceIp) {
        return DSL.field("cast({0} as inet)", String.class, DSL.val(sourceIp));
    }
}
